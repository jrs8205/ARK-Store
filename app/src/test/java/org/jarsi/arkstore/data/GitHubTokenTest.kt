package org.jarsi.arkstore.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubTokenTest {

    @Test
    fun theTokenGoesToTheApiAlone() {
        assertEquals("Bearer abc", GitHubToken.authorization("https://api.github.com/repos/o/r", "abc"))
        assertNull(GitHubToken.authorization("https://github.com/o/r/releases/download/v1/a.apk", "abc"))
        assertNull(GitHubToken.authorization("https://raw.githubusercontent.com/o/r/main/x", "abc"))
        assertNull(GitHubToken.authorization("https://objects.githubusercontent.com/x", "abc"))
        assertNull(GitHubToken.authorization("https://api.github.com.example.org/", "abc"))
        assertNull(GitHubToken.authorization("http://api.github.com/rate_limit", "abc"))
        assertNull(GitHubToken.authorization("https://api.github.com/rate_limit", null))
        assertNull(GitHubToken.authorization("not a url", "abc"))
    }

    @Test
    fun aTokenIsTakenWithoutTheSpaceAroundIt() {
        assertEquals("github_pat_x", GitHubToken.tidy("  github_pat_x\n"))
        assertNull(GitHubToken.tidy(""))
        assertNull(GitHubToken.tidy("   "))
        assertNull(GitHubToken.tidy("two words"))
    }

    @Test
    fun theHourlyLimitIsReadFromTheRateLimitAnswer() {
        val answer = """{"resources":{"core":{"limit":5000,"used":1,"remaining":4999,"reset":1}},"rate":{"limit":5000}}"""
        assertEquals(5000, GitHubToken.limitOf(answer))
        assertNull(GitHubToken.limitOf("""{"rate":{"limit":60}}"""))
        assertNull(GitHubToken.limitOf("not json"))
    }
}
