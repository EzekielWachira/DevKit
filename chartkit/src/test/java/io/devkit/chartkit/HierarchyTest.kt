package io.devkit.chartkit

import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.hierarchy.ChartHierarchy
import io.devkit.chartkit.hierarchy.HierarchyValueGuard
import io.devkit.chartkit.hierarchy.HierarchyValuePolicy
import io.devkit.chartkit.hierarchy.SunburstLayout
import io.devkit.chartkit.hierarchy.TreemapLayout
import io.devkit.chartkit.hierarchy.TreemapSpacing
import io.devkit.chartkit.hierarchy.buildHierarchy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** A consumer model, deliberately not a ChartKit type. */
private class Node(
    val name: String,
    val amount: Double? = null,
    val children: List<Node> = emptyList(),
    val id: String = name,
)

private fun tree(root: Node, policy: HierarchyValuePolicy = HierarchyValuePolicy.AggregateChildren) =
    buildHierarchy(
        root = root,
        children = { it.children },
        value = { it.amount },
        label = { it.name },
        key = { it.id },
        valuePolicy = policy,
    )

/**
 * Hierarchy normalisation: identity, depth, aggregation, and the malformed
 * inputs that would otherwise recurse forever.
 */
class HierarchyTest {

    private val company = Node(
        "Company",
        children = listOf(
            Node(
                "Engineering",
                children = listOf(
                    Node("Android", 40.0),
                    Node("iOS", 30.0),
                    Node("Web", 30.0),
                ),
            ),
            Node("Sales", children = listOf(Node("EMEA", 60.0), Node("AMER", 40.0))),
        ),
    )

    @Test
    fun `a parent's value is the sum of its children`() {
        val hierarchy = tree(company)
        assertEquals(200.0, hierarchy.root.value, 1e-9)
        assertEquals(100.0, hierarchy.node("Engineering")!!.value, 1e-9)
    }

    @Test
    fun `depth counts levels from the root`() {
        val hierarchy = tree(company)
        assertEquals(0, hierarchy.root.depth)
        assertEquals(1, hierarchy.node("Engineering")!!.depth)
        assertEquals(2, hierarchy.node("Android")!!.depth)
        assertEquals(2, hierarchy.maxDepth)
    }

    @Test
    fun `a node's path names every ancestor`() {
        val android = tree(company).node("Android")!!
        assertEquals(listOf("Company", "Engineering", "Android"), android.path)
    }

    @Test
    fun `every node knows its parent`() {
        val hierarchy = tree(company)
        assertEquals("Engineering", hierarchy.node("Android")!!.parent?.label)
        assertNull(hierarchy.root.parent)
    }

    @Test
    fun `shares are computed against the parent and the root`() {
        val hierarchy = tree(company)
        val android = hierarchy.node("Android")!!
        assertEquals(0.4, android.fractionOfParent, 1e-9)
        assertEquals(0.2, android.fractionOf(hierarchy.root), 1e-9)
    }

    @Test
    fun `the trail runs from the root down to the node`() {
        val trail = tree(company).trail("Android").map { it.label }
        assertEquals(listOf("Company", "Engineering", "Android"), trail)
    }

    @Test
    fun `a self-referential node is cut rather than followed forever`() {
        // A "tree" whose child list contains the node itself. Without the cycle
        // guard this recurses until the stack runs out.
        val children = ArrayList<Node>()
        val loop = Node("Loop", 5.0, children)
        children += loop

        val hierarchy = buildHierarchy(
            root = loop,
            children = { it.children },
            value = { it.amount },
            label = { it.name },
        )
        assertEquals(1, hierarchy.cyclesBroken)
        assertEquals(1, hierarchy.nodes.size)
    }

    @Test
    fun `a longer cycle is cut where it closes`() {
        // A -> B -> A, built so the *same object* reappears on the path.
        val backToA = ArrayList<Node>()
        val b = Node("B", children = backToA)
        val a = Node("A", children = listOf(b))
        backToA += a

        val hierarchy = buildHierarchy(
            root = a,
            children = { it.children },
            value = { it.amount },
            label = { it.name },
        )
        assertEquals(1, hierarchy.cyclesBroken)
        assertEquals(listOf("A", "B"), hierarchy.nodes.map { it.label })
    }

    @Test
    fun `two structurally equal siblings are two nodes, not a cycle`() {
        // Equality is not identity: two distinct objects that compare equal are
        // legitimate data, and rejecting the second would delete it silently.
        val hierarchy = buildHierarchy(
            root = Node("Root", children = listOf(Node("Team", 1.0), Node("Team", 1.0))),
            children = { it.children },
            value = { it.amount },
            label = { it.name },
        )
        assertEquals(0, hierarchy.cyclesBroken)
        assertEquals(2, hierarchy.root.children.size)
    }

    @Test
    fun `extreme depth is truncated rather than overflowing the stack`() {
        // Ten thousand levels: an iterative build would be fine, and the cap is
        // what makes the recursive one safe.
        var node = Node("leaf", 1.0)
        repeat(5_000) { node = Node("level", children = listOf(node)) }
        val hierarchy = buildHierarchy(
            root = node,
            children = { it.children },
            value = { it.amount },
            label = { it.name },
            maxDepth = 8,
        )
        assertEquals(8, hierarchy.maxDepth)
        assertTrue(hierarchy.truncatedAtDepth > 0)
    }

    @Test
    fun `a negative value is dropped rather than drawn`() {
        val hierarchy = buildHierarchy(
            root = Node("Root", children = listOf(Node("Good", 10.0), Node("Bad", -5.0))),
            children = { it.children },
            value = { it.amount },
            label = { it.name },
        )
        assertEquals(1, hierarchy.droppedValues)
        assertEquals(10.0, hierarchy.root.value, 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative value can be rejected instead`() {
        buildHierarchy(
            root = Node("Root", children = listOf(Node("Bad", -5.0))),
            children = { it.children },
            value = { it.amount },
            label = { it.name },
            valueGuard = HierarchyValueGuard.Reject,
        )
    }

    @Test
    fun `an explicit parent value is reported as a conflict but not honoured`() {
        val hierarchy = tree(
            Node("Root", amount = 999.0, children = listOf(Node("A", 10.0), Node("B", 10.0))),
        )
        assertEquals(20.0, hierarchy.root.value, 1e-9)
        assertEquals(listOf("Root"), hierarchy.valueConflicts)
    }

    @Test
    fun `PreferExplicit honours the parent's own value`() {
        val hierarchy = tree(
            Node("Root", amount = 999.0, children = listOf(Node("A", 10.0))),
            policy = HierarchyValuePolicy.PreferExplicit,
        )
        assertEquals(999.0, hierarchy.root.value, 1e-9)
    }

    @Test
    fun `duplicate keys are made unique rather than merging two nodes`() {
        val hierarchy = buildHierarchy(
            root = Node("Root", children = listOf(Node("A", 1.0, id = "x"), Node("B", 2.0, id = "x"))),
            children = { it.children },
            value = { it.amount },
            label = { it.name },
            key = { it.id },
        )
        assertEquals(3, hierarchy.nodes.size)
        assertEquals(2, hierarchy.nodes.map { it.id }.distinct().size - 1)
    }

    @Test
    fun `a forest gets one synthetic root`() {
        val hierarchy = buildHierarchy(
            roots = listOf(Node("A", 1.0), Node("B", 3.0)),
            children = { it.children },
            value = { it.amount },
            label = { it.name },
            rootLabel = "All",
        )
        assertEquals("All", hierarchy.root.label)
        assertEquals(4.0, hierarchy.root.value, 1e-9)
        assertEquals(2, hierarchy.root.children.size)
    }

    @Test
    fun `a leaf-only hierarchy still has a value`() {
        val hierarchy = buildHierarchy(
            root = Node("Only", 7.0),
            children = { it.children },
            value = { it.amount },
            label = { it.name },
        )
        assertEquals(7.0, hierarchy.root.value, 1e-9)
        assertEquals(0, hierarchy.maxDepth)
    }

    @Test
    fun `an empty hierarchy is empty rather than an error`() {
        val hierarchy = buildHierarchy(
            root = Node("Nothing"),
            children = { it.children },
            value = { it.amount },
            label = { it.name },
        )
        assertEquals(0.0, hierarchy.root.value, 1e-9)
        assertTrue(hierarchy.isEmpty)
    }

    @Test
    fun `the default depth cap is generous enough for real trees`() {
        assertTrue(ChartHierarchy.DEFAULT_MAX_DEPTH >= 16)
    }
}

/**
 * Treemap packing: the three properties a reader relies on without knowing it.
 *
 * Areas in proportion, nothing overlapping, and nothing outside the frame.
 */
class TreemapLayoutTest {

    private val bounds = ChartRect(0f, 0f, 400f, 300f)

    private fun rects(values: List<Double>) = TreemapLayout.squarify(values, bounds)

    @Test
    fun `the rectangles fill the bounds and no more`() {
        val result = rects(listOf(5.0, 3.0, 2.0, 1.0))
        result.forEach { rect ->
            assertTrue("left $rect", rect.left >= bounds.left - 0.01f)
            assertTrue("top $rect", rect.top >= bounds.top - 0.01f)
            assertTrue("right $rect", rect.right <= bounds.right + 0.01f)
            assertTrue("bottom $rect", rect.bottom <= bounds.bottom + 0.01f)
        }
    }

    @Test
    fun `the areas sum to the whole frame`() {
        val total = rects(listOf(5.0, 3.0, 2.0, 1.0)).sumOf { (it.width * it.height).toDouble() }
        assertEquals(bounds.width * bounds.height.toDouble(), total, 1.0)
    }

    @Test
    fun `each area is proportional to its value`() {
        val values = listOf(50.0, 25.0, 15.0, 10.0)
        val frameArea = bounds.width.toDouble() * bounds.height
        rects(values).forEachIndexed { index, rect ->
            val expected = values[index] / values.sum() * frameArea
            val actual = rect.width.toDouble() * rect.height
            assertEquals("value ${values[index]}", expected, actual, expected * 0.02)
        }
    }

    @Test
    fun `no two rectangles overlap`() {
        val result = rects(listOf(8.0, 6.0, 5.0, 4.0, 3.0, 2.0, 1.0))
        for (i in result.indices) {
            for (j in i + 1 until result.size) {
                val a = result[i].normalized
                val b = result[j].normalized
                val separated = a.right <= b.left + 0.01f || b.right <= a.left + 0.01f ||
                    a.bottom <= b.top + 0.01f || b.bottom <= a.top + 0.01f
                assertTrue("$a overlaps $b", separated)
            }
        }
    }

    @Test
    fun `a single item fills the frame`() {
        val rect = rects(listOf(1.0)).single()
        assertEquals(bounds.width, rect.width, 0.01f)
        assertEquals(bounds.height, rect.height, 0.01f)
    }

    @Test
    fun `zero values produce zero-area rectangles rather than a division by zero`() {
        val result = rects(listOf(0.0, 0.0))
        result.forEach { assertEquals(0.0, (it.width * it.height).toDouble(), 1e-6) }
    }

    @Test
    fun `squarifying keeps aspect ratios far better than slicing`() {
        // One dominant value and several small ones is the case the naive
        // algorithm turns into slivers.
        val result = rects(listOf(80.0, 5.0, 5.0, 4.0, 3.0, 3.0))
        val worst = result.filter { it.width > 0f && it.height > 0f }
            .maxOf { maxOf(it.width / it.height, it.height / it.width) }
        assertTrue("worst aspect ratio was $worst", worst < 8f)
    }

    @Test
    fun `nested levels are laid out inside their parents`() {
        val hierarchy = buildHierarchy(
            root = Node(
                "Root",
                children = listOf(
                    Node("A", children = listOf(Node("A1", 3.0), Node("A2", 1.0))),
                    Node("B", 4.0),
                ),
            ),
            children = { it.children },
            value = { it.amount },
            label = { it.name },
        )
        val tiles = TreemapLayout.layout(
            root = hierarchy.root,
            bounds = bounds,
            maxDepth = 2,
            spacing = TreemapSpacing(padding = 0f, headerHeight = 0f, tileGap = 0f),
        )
        val parent = tiles.first { it.node.label == "A" }.bounds.normalized
        listOf("A1", "A2").forEach { name ->
            val child = tiles.first { it.node.label == name }.bounds.normalized
            assertTrue(
                "$name is outside A",
                child.left >= parent.left - 0.5f && child.right <= parent.right + 0.5f &&
                    child.top >= parent.top - 0.5f && child.bottom <= parent.bottom + 0.5f,
            )
        }
    }
}

/** Sunburst geometry: angles from value, radius from depth, and hit testing. */
class SunburstLayoutTest {

    private val hierarchy = buildHierarchy(
        root = Node(
            "Root",
            children = listOf(
                Node("A", children = listOf(Node("A1", 30.0), Node("A2", 10.0))),
                Node("B", 60.0),
            ),
        ),
        children = { it.children },
        value = { it.amount },
        label = { it.name },
    )

    private fun arcs() = SunburstLayout.layout(
        root = hierarchy.root,
        innerRadius = 20f,
        outerRadius = 100f,
        ringSpacing = 0f,
        sliceGap = 0f,
    )

    @Test
    fun `angles are allocated in proportion to value`() {
        val a = arcs().first { it.node.label == "A" }
        val b = arcs().first { it.node.label == "B" }
        assertEquals(144f, a.sweepAngle, 0.01f)
        assertEquals(216f, b.sweepAngle, 0.01f)
    }

    @Test
    fun `the rings sum to a full circle`() {
        val total = arcs().filter { it.level == 0 }.sumOf { it.sweepAngle.toDouble() }
        assertEquals(360.0, total, 0.01)
    }

    @Test
    fun `depth becomes ring index`() {
        val arcs = arcs()
        assertEquals(0, arcs.first { it.node.label == "A" }.level)
        assertEquals(1, arcs.first { it.node.label == "A1" }.level)
    }

    @Test
    fun `a child's arc is contained within its parent's`() {
        val a = arcs().first { it.node.label == "A" }
        arcs().filter { it.node.parent?.label == "A" }.forEach { child ->
            assertTrue(child.startAngle >= a.startAngle - 0.01f)
            assertTrue(child.startAngle + child.sweepAngle <= a.startAngle + a.sweepAngle + 0.01f)
        }
    }

    @Test
    fun `rings divide the radius evenly`() {
        val arcs = arcs()
        val outer = arcs.first { it.level == 0 }
        val inner = arcs.first { it.level == 1 }
        assertEquals(20f, outer.innerRadius, 0.01f)
        assertEquals(60f, outer.outerRadius, 0.01f)
        assertEquals(60f, inner.innerRadius, 0.01f)
        assertEquals(100f, inner.outerRadius, 0.01f)
    }

    @Test
    fun `hit testing finds the arc under an angle and radius`() {
        val arcs = arcs()
        // 10 degrees, in the first ring: inside A, which starts at zero.
        val hit = SunburstLayout.hitTest(arcs, angleDegrees = 10f, radius = 40f)
        assertEquals("A", hit?.node?.label)
    }

    @Test
    fun `the centre belongs to no arc`() {
        assertNull(SunburstLayout.hitTest(arcs(), angleDegrees = 10f, radius = 5f))
    }

    @Test
    fun `drilling into a node makes it the whole circle`() {
        val engineering = hierarchy.node(hierarchy.root.children.first().id)!!
        val drilled = SunburstLayout.layout(
            root = engineering,
            innerRadius = 0f,
            outerRadius = 100f,
            ringSpacing = 0f,
            sliceGap = 0f,
        )
        val total = drilled.filter { it.level == 0 }.sumOf { it.sweepAngle.toDouble() }
        assertEquals(360.0, total, 0.01)
        assertTrue(abs(drilled.first().innerRadius) < 0.01f)
    }
}
