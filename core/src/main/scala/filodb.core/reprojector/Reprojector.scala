package filodb.core.reprojector

import com.typesafe.scalalogging.slf4j.StrictLogging
import org.velvia.filo.RowIngestSupport
import scala.concurrent.{ExecutionContext, Future}

import filodb.core._
import filodb.core.columnstore.{ColumnStore, RowReader, RowWriterSegment, Segment}
import filodb.core.metadata.{Dataset, Column, RichProjection}

/**
 * The Reprojector flushes rows out of the MemTable and writes out Segments to the ColumnStore.
 * All of the work should be done asynchronously.
 * The reprojector should be stateless.  It takes MemTables and creates Futures for reprojection tasks.
 */
trait Reprojector {
  import MemTable.IngestionSetup
  import RowReader._

  /**
   * Does reprojection (columnar flushes from memtable) for a single dataset.
   * Should be completely stateless.
   * Does not need to reproject all the rows from the Locked memtable, but should progressively
   * delete rows from the memtable until there are none left.  This is how the reprojector marks progress:
   * by deleting rows that has been committed to ColumnStore.
   *
   * Failures:
   * The Scheduler only schedules one reprojection task at a time per (dataset, version), so if this fails,
   * then it can be rerun.
   *
   * Most likely this will involve scheduling a whole bunch of futures to write segments.
   * Be careful to do too much work, newTask is supposed to not take too much CPU time and use Futures
   * to do work asynchronously.  Also, scheduling too many futures leads to long blocking time and
   * memory issues.
   *
   * @returns a Future[Seq[Response]], representing states of individual segment flushes.
   */
  def newTask(memTable: MemTable, dataset: Types.TableName, version: Int): Future[Seq[Response]] = {
    import Column.ColumnType._

    val setup = memTable.getIngestionSetup(dataset, version).getOrElse(
                  throw new IllegalArgumentException(s"Could not find $dataset/$version"))
    setup.schema(setup.sortColumnNum).columnType match {
      case LongColumn    => reproject[Long](memTable, setup, version)
      case other: Column.ColumnType => throw new RuntimeException("Illegal sort key type $other")
    }
  }

  // The inner, typed reprojection task launcher that must be implemented.
  def reproject[K: TypedFieldExtractor](memTable: MemTable, setup: IngestionSetup, version: Int):
      Future[Seq[Response]]
}

/**
 * Default reprojector, which scans the Locked memtable, turning them into segments for flushing,
 * using fixed segment widths
 *
 * @param maxRows the maximum number of rows to reproject for each reprojection task.
 */
class DefaultReprojector(columnStore: ColumnStore,
                         maxRows: Int = 100000)
                        (implicit ec: ExecutionContext) extends Reprojector with StrictLogging {
  import MemTable._
  import Types._
  import RowReader._
  import Iterators._

  // PERF/TODO: Maybe we should pass in an Iterator[RowReader], and extract partition and sort keys
  // out.  Heck we could create a custom FiloRowReader which has methods to extract this out.
  // Might be faster than creating a Tuple3 for every row... or not, for complex sort and partition keys
  def chunkize[K](rows: Iterator[(PartitionKey, K, RowReader)],
                  setup: IngestionSetup): Iterator[Segment[K]] = {
    implicit val helper = setup.helper[K]
    var numRows = 0
    rows.sortedGroupBy { case (partition, sortKey, row) =>
      // lazy grouping of partition/segment from the sortKey
      (partition, helper.getSegment(sortKey))
    }.map { case ((partition, (segStart, segUntil)), segmentRowsIt) =>
      // For each segment grouping of rows... set up a Segment
      val keyRange = KeyRange(setup.dataset.name, partition, segStart, segUntil)
      val segment = new RowWriterSegment(keyRange, setup.schema, RowReaderSupport)
      logger.debug(s"Created new segment $segment for encoding...")

      // Group rows into chunk sized bytes and add to segment
      segmentRowsIt.grouped(setup.dataset.options.chunkSize).foreach { chunkRowsIt =>
        val chunkRows = chunkRowsIt.toSeq
        segment.addRowsAsChunk(chunkRows)
        numRows += chunkRows.length
      }
      segment
    // NOTE: semantics below make sure we write entire segments, but takeWhile() wastes
    // the last segment produced.  TODO: what we really need is a takeUntil.
    }.takeWhile(s => numRows < maxRows)
  }

  def reproject[K: TypedFieldExtractor](memTable: MemTable, setup: IngestionSetup, version: Int):
      Future[Seq[Response]] = {
    implicit val helper = setup.helper[K]
    val datasetName = setup.dataset.name
    val projection = RichProjection(setup.dataset, setup.schema)
    val segments = chunkize(memTable.readAllRows[K](datasetName, version, Locked), setup)
    val segmentTasks: Seq[Future[Response]] = segments.map { segment =>
      for { resp <- columnStore.appendSegment(projection, segment, version) if resp == Success }
      yield {
        logger.debug(s"Finished merging segment ${segment.keyRange}, version $version...")
        memTable.removeRows(segment.keyRange, version)
        logger.debug(s"Removed rows for segment $segment from Locked table...")
        resp
      }
    }.toSeq
    Future.sequence(segmentTasks)
  }
}

// Grrr... only needed as long as Filo still uses old RowIngestSupport
object RowReaderSupport extends RowIngestSupport[RowReader] {
  type R = RowReader
  def getString(row: R, columnNo: Int): Option[String] =
    if (row.notNull(columnNo)) Some(row.getString(columnNo)) else None
  def getInt(row: R, columnNo: Int): Option[Int] =
    if (row.notNull(columnNo)) Some(row.getInt(columnNo)) else None
  def getLong(row: R, columnNo: Int): Option[Long] =
    if (row.notNull(columnNo)) Some(row.getLong(columnNo)) else None
  def getDouble(row: R, columnNo: Int): Option[Double] =
    if (row.notNull(columnNo)) Some(row.getDouble(columnNo)) else None
}

// From http://stackoverflow.com/questions/10642337/is-there-are-iterative-version-of-groupby-in-scala
object Iterators {
  implicit class RichIterator[T](origIt: Iterator[T]) {
    def sortedGroupBy[B](func: T => B): Iterator[(B, Iterator[T])] = new Iterator[(B, Iterator[T])] {
      var iter = origIt
      def hasNext: Boolean = iter.hasNext
      def next: (B, Iterator[T]) = {
        val first = iter.next()
        val firstValue = func(first)
        val (i1,i2) = iter.span(el => func(el) == firstValue)
        iter = i2
        (firstValue, Iterator.single(first) ++ i1)
      }
    }
  }
}