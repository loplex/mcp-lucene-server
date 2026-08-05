package cz.loplex.lucenemcp.tools

import cz.loplex.lucenemcp.core.*
import cz.loplex.lucenemcp.index.*
import cz.loplex.lucenemcp.ast.*
import cz.loplex.lucenemcp.tools.*
import cz.loplex.lucenemcp.*

import cz.loplex.lucenemcp.core.*
import cz.loplex.lucenemcp.index.*
import cz.loplex.lucenemcp.ast.*
import cz.loplex.lucenemcp.tools.*

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FindImplementationsToolTest {

    @Test
    fun `finds a Kotlin superclass and interface implementer separately`(@TempDir tempDir: File) {
        File(tempDir, "App.kt").writeText(
            """
            interface Drawable
            abstract class Shape(val r: Double)
            class Circle(r: Double) : Shape(r), Drawable
            """.trimIndent()
        )

        val shapeResult = runFindImplementations(tempDir, "Shape", 50)
        assertTrue(shapeResult.contains("App.kt:3"))
        assertTrue(shapeResult.contains("[extends]"))
        assertTrue(shapeResult.contains("Found 1 implementation"))

        val drawableResult = runFindImplementations(tempDir, "Drawable", 50)
        assertTrue(drawableResult.contains("App.kt:3"))
        assertTrue(drawableResult.contains("[implements]"))
    }

    @Test
    fun `a Kotlin inherent (no supertype) class is not reported as implementing anything`(@TempDir tempDir: File) {
        File(tempDir, "App.kt").writeText("class Standalone\n")

        val result = runFindImplementations(tempDir, "Standalone", 50)
        assertTrue(result.contains("No implementations"))
    }

    @Test
    fun `finds a Java superclass and interface separately`(@TempDir tempDir: File) {
        File(tempDir, "Circle.java").writeText(
            """
            public class Circle extends Shape implements Drawable {
            }
            """.trimIndent()
        )

        val shapeResult = runFindImplementations(tempDir, "Shape", 50)
        assertTrue(shapeResult.contains("Circle.java:1"))
        assertTrue(shapeResult.contains("[extends]"))

        val drawableResult = runFindImplementations(tempDir, "Drawable", 50)
        assertTrue(drawableResult.contains("Circle.java:1"))
        assertTrue(drawableResult.contains("[implements]"))
    }

    @Test
    fun `finds a TypeScript extends and implements clause separately, not JS's plain extends`(@TempDir tempDir: File) {
        File(tempDir, "shapes.ts").writeText(
            """
            class Circle extends Shape implements Drawable {
            }
            """.trimIndent()
        )

        val shapeResult = runFindImplementations(tempDir, "Shape", 50)
        assertTrue(shapeResult.contains("shapes.ts:1"))
        assertTrue(shapeResult.contains("[extends]"))

        val drawableResult = runFindImplementations(tempDir, "Drawable", 50)
        assertTrue(drawableResult.contains("shapes.ts:1"))
        assertTrue(drawableResult.contains("[implements]"))
    }

    @Test
    fun `finds a JavaScript extends clause (no implements concept in JS)`(@TempDir tempDir: File) {
        File(tempDir, "shapes.js").writeText(
            """
            class Circle extends Shape {
            }
            """.trimIndent()
        )

        val result = runFindImplementations(tempDir, "Shape", 50)
        assertTrue(result.contains("shapes.js:1"))
        assertTrue(result.contains("[extends]"))
    }

    @Test
    fun `finds Python multiple inheritance`(@TempDir tempDir: File) {
        File(tempDir, "shapes.py").writeText(
            """
            class Circle(Shape, Drawable):
                pass
            """.trimIndent()
        )

        val shapeResult = runFindImplementations(tempDir, "Shape", 50)
        assertTrue(shapeResult.contains("shapes.py:1"))

        val drawableResult = runFindImplementations(tempDir, "Drawable", 50)
        assertTrue(drawableResult.contains("shapes.py:1"))
    }

    @Test
    fun `finds a Rust trait impl but not an inherent impl`(@TempDir tempDir: File) {
        File(tempDir, "shapes.rs").writeText(
            """
            trait Shape {}
            struct Circle;
            impl Shape for Circle {}
            impl Circle {
                fn area(&self) {}
            }
            """.trimIndent()
        )

        val result = runFindImplementations(tempDir, "Shape", 50)
        assertTrue(result.contains("shapes.rs:3"))
        assertTrue(result.contains("[implements]"))
        assertTrue(result.contains("Found 1 implementation"))
    }

    @Test
    fun `Go structural interfaces are not supported`(@TempDir tempDir: File) {
        File(tempDir, "shapes.go").writeText(
            """
            type Shape interface {
                Area() float64
            }
            type Circle struct {
                r float64
            }
            func (c Circle) Area() float64 {
                return 3.14 * c.r * c.r
            }
            """.trimIndent()
        )

        val result = runFindImplementations(tempDir, "Shape", 50)
        assertTrue(result.contains("No implementations"))
    }

    @Test
    fun `a symbol only mentioned in a comment or string is not an implementer`(@TempDir tempDir: File) {
        File(tempDir, "App.kt").writeText(
            """
            interface Shape
            // class FakeCircle : Shape
            val commentTrap = "class StringCircle : Shape"
            """.trimIndent()
        )

        val result = runFindImplementations(tempDir, "Shape", 50)
        assertTrue(result.contains("No implementations"))
    }

    @Test
    fun `blank type is a clean error`(@TempDir tempDir: File) {
        val result = runFindImplementations(tempDir, "  ", 50)
        assertTrue(result.contains("Missing required argument"))
    }

    @Test
    fun `non-identifier type is rejected with a clean error`(@TempDir tempDir: File) {
        val result = runFindImplementations(tempDir, "not an identifier!", 50)
        assertTrue(result.contains("Invalid type"))
    }

    @Test
    fun `maxMatches caps the number of returned implementations`(@TempDir tempDir: File) {
        val sb = StringBuilder()
        repeat(5) { i -> sb.append("class Impl$i : Base\n") }
        File(tempDir, "many.kt").writeText(sb.toString())

        val matches = findImplementations(tempDir, "Base", maxMatches = 2)
        assertTrue(matches.size <= 2)
    }

    @Test
    fun `a shared AstCache still reflects file edits across calls`(@TempDir tempDir: File) {
        val file = File(tempDir, "App.kt")
        file.writeText("interface Shape\nclass Circle\n")
        val cache = AstCache()

        val before = runFindImplementations(tempDir, "Shape", 50, cache)
        assertTrue(before.contains("No implementations"))

        Thread.sleep(1100) // ensure a distinct filesystem mtime (1s resolution on some filesystems)
        file.writeText("interface Shape\nclass Circle : Shape\n")
        val after = runFindImplementations(tempDir, "Shape", 50, cache)

        assertTrue(after.contains("App.kt:2"))
        assertTrue(after.contains("[implements]"))
    }
}
