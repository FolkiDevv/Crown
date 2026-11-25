package io.nekohasekai.sagernet.simple.repair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectorRunSetTest {

    private fun unused(): List<Long> = error("the planned build must not be asked while a build runs")

    @Test
    fun aRunningSelectorRunsItsBuildOnly() {
        assertEquals(setOf(1L, 2L), SelectorRunSet.of(connected = true, lastBuilt = listOf(1L, 2L), plannedBuild = ::unused))
    }

    @Test
    fun aCappedBuildWhoseMembersAllFailDoesNotAnswer() {
        // Eligible members 1..5, build limit 2: the core runs 1 and 2; only 4 (outside the build) answers.
        val members = SelectorRunSet.of(connected = true, lastBuilt = listOf(1L, 2L), plannedBuild = ::unused)
        assertFalse(SelectorRunSet.answers(members, answering = setOf(4L)))
    }

    @Test
    fun aRunningBuildAnswersWhenOneOfItsMembersDoes() {
        val members = SelectorRunSet.of(connected = true, lastBuilt = listOf(1L, 2L), plannedBuild = ::unused)
        assertTrue(SelectorRunSet.answers(members, answering = setOf(2L, 4L)))
    }

    @Test
    fun withTheVpnOffThePlannedBuildCounts() {
        val members = SelectorRunSet.of(connected = false, lastBuilt = listOf(1L, 2L)) { listOf(3L, 4L) }
        assertEquals(setOf(3L, 4L), members)
        assertTrue(SelectorRunSet.answers(members, answering = setOf(4L)))
        assertFalse(SelectorRunSet.answers(members, answering = setOf(1L)))
    }

    @Test
    fun beforeAFirstBuildThePlannedBuildCounts() {
        assertEquals(setOf(3L), SelectorRunSet.of(connected = true, lastBuilt = emptyList()) { listOf(3L) })
    }

    @Test
    fun anEmptyPlanAnswersNothing() {
        val members = SelectorRunSet.of(connected = false, lastBuilt = emptyList()) { emptyList() }
        assertFalse(SelectorRunSet.answers(members, answering = setOf(1L, 2L)))
    }
}
