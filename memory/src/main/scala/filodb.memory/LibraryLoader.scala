package filodb.memory

import java.nio.file.Files

import scala.reflect.ClassTag

import jnr.ffi.{LibraryLoader => FFILibLoader}

trait RustLib {
  def double_input(in: Int): Int
}

object LibraryLoader {
  /**
   * Loads a native library using JFFI and the given Trait/Interface for native library calling as a class param,
   * from the given shared library which is stored in the JAR classpath at the root.
   * Will extract the shared library into a temporary directory first.
   * This allows loading via JFFI of native libraries stored in the same jar for portability without configuring
   * any deployment or native lib paths etc.
   *
   * For example, on Linux, calling loadFromJar[CryptoApi]("crypto") will extract the resource
   * "libcrypto.so" from the root of the JAR/classpath to a temp file and load it using an object conforming
   * to CryptoApi.
   */
  def loadFromJar[T: ClassTag](libraryName: String): T = {
    val libName = System.mapLibraryName(libraryName)
    val libUrl = getClass.getResource("/" + libName)
    if (Option(libUrl).isEmpty) {
      throw new RuntimeException(s"Not able to find resource /$libName in classpath/JAR...")
    }
    val tmpDir = Files.createTempDirectory("nativelib").toFile
    tmpDir.deleteOnExit()
    val nativeLibTmpFile = new java.io.File(tmpDir, libName)
    nativeLibTmpFile.deleteOnExit()
    Files.copy(libUrl.openStream(), nativeLibTmpFile.toPath)

    // Setting the library.path might not be necessary as we are passing aboslute path of extracted library
    sys.props.put("jnr.ffi.library.path", tmpDir.getAbsolutePath)
    val clazz = implicitly[ClassTag[T]].runtimeClass
    FFILibLoader.create(clazz).load(nativeLibTmpFile.getAbsolutePath).asInstanceOf[T]
  }

  // Load the Rust native compressed vec library here by default
}