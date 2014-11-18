package faba.test

import annotations._
import data.InferenceData

import org.objectweb.asm._
import org.scalatest.{Matchers, FunSuite}

import faba.MainProcessor
import faba.data._
import faba.source.ClassSource

// TODO - it is for stable keys only for now
class InferenceSuite extends FunSuite with Matchers {

  test("InferenceData.class") {
    checkInference(classOf[InferenceData])
  }

  def checkInference(classes: Class[_]*) {
    val annotations = new MainProcessor().process(ClassSource(classes:_*))
    for (jClass <- classes; jMethod <- jClass.getDeclaredMethods) {
      val method = Method(Type.getType(jClass).getInternalName, jMethod.getName, Type.getMethodDescriptor(jMethod))

      for {(anns, i) <- jMethod.getParameterAnnotations.zipWithIndex} {

        val notNull = anns.exists(_.annotationType == classOf[ExpectNotNull])
        assert(
          annotations.notNulls.contains(Key(method, In(i), stable = true)) == notNull,
          s"@NotNull'$jClass $jMethod #$i'"
        )

        val expectedNullable = anns.exists(_.annotationType == classOf[ExpectNullable])
        val actualNullable: Boolean = annotations.nullable.contains(Key(method, In(i), stable = true))
        assert(
          actualNullable == expectedNullable,
          s"'$jClass $jMethod #$i': ${describeSimpleMismatch(expectedNullable, actualNullable, "@Nullable")}"
        )
      }

      annotations.notNulls(Key(method, Out, stable = true)) should equal (jMethod.getAnnotation(classOf[ExpectNotNull]) != null)
      annotations.contracts.get(Key(method, Out, stable = true)) should equal (Option(jMethod.getAnnotation(classOf[ExpectContract])).map(_.value))
    }
  }

  private def describeSimpleMismatch(expected: Boolean, actual: Boolean, annotationName: String) = {
    val expectedString = if (expected) annotationName else "null"
    val actualString = if (actual) annotationName else "null"
    s"$expectedString (expected) != $actualString (actual)"
  }
}
