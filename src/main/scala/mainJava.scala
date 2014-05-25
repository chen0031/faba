package faba.java

import java.io.{PrintWriter, File}
import java.util
import scala.collection.JavaConversions._

object Main {
  def main(args: Array[String]) {
    if (args.length != 2) {
      System.out.println("Usage: faba.java.Main inputJar outDir")
    }
    else {
      new Main().process(new JarFileSource(new File(args(0))), args(1))
    }
  }

  private[java] final val valueLattice: ELattice[Value] = new ELattice[Value](Value.Bot, Value.Top)
}

class Main extends JavaProcessor {

  def printToFile(f: File)(op: PrintWriter => Unit) {
    if (f.getParentFile != null) {
      f.getParentFile.mkdirs()
    }
    val p = new PrintWriter(f)
    try { op(p) } finally { p.close() }
  }

  def process(source: Source, outDir: String) {
    val indexStart: Long = System.currentTimeMillis
    source.process(this)
    val indexEnd: Long = System.currentTimeMillis
    val solutions: util.Map[Key, Value] = solver.solve()

    val solvingEnd: Long = System.currentTimeMillis

    printToFile(new File(outDir + ".txt")) { out =>
      for {d <- solutions.entrySet().iterator()} {
        if (d.getValue != Value.Top && d.getValue != Value.Bot) {
          val key: Key = d.getKey
          out.println(Util.annotationKey(key.method, extras.get(key.method), key.direction));
        }
      }
    }

    println(s"indexing took ${(indexEnd - indexStart) / 1000.0} sec")
    println(s"solving took ${(solvingEnd - indexEnd) / 1000.0} sec")

  }
}
