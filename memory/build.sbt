// Build script for integrating rust stuff into memory module
//

lazy val buildRust = taskKey[Unit]("Build and package Rust libs")

buildRust := {
  val s: TaskStreams = streams.value
  val shell = if (sys.props("os.name").contains("Windows")) Seq("cmd", "/c") else Seq("bash", "-c")
  val resourceDir = baseDirectory.value / "src" / "main" / "resources"
  val rustDir = baseDirectory.value / ".." / "rust"

  val buildRust = Process("cargo" :: "build" :: "--release" :: Nil, rustDir,
                          "RUSTFLAGS" -> "-C target-feature=+avx2")

  val validExts = Seq(".so", ".dylib")
  val filesToCopy = validExts.map { ext => rustDir / "target" / "release" / s"libcompvec$ext" }
                             .filter(_.exists)

  val copyLibs = shell :+ s"cd $rustDir && cp ${filesToCopy.mkString(" ")} $resourceDir/"
  s.log.info(s"building rust libs at $rustDir...")
  if ((buildRust #&& copyLibs !) == 0) {
    s.log.success("rust build successful!")
  } else {
    throw new IllegalStateException("rust build failed!")
  }
}

(compile in Compile) := ((compile in Compile).dependsOn(buildRust)).value
