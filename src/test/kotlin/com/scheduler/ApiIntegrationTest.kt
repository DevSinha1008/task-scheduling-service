package com.scheduler

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertTrue

/**
 * End-to-end HTTP tests through the full filter chain.
 *
 * MockMvc drives real requests through Spring Security and the controllers
 * without binding a network port, so the auth rules under test are the ones
 * that actually ship rather than a mocked approximation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiIntegrationTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper

    /** Register a unique user and return its bearer token. */
    private fun authenticate(): String {
        val username = "user-${System.nanoTime()}"
        val body = mapper.writeValueAsString(mapOf("username" to username, "password" to "password123"))
        val response = mvc.perform(
            post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isCreated).andReturn().response.contentAsString

        @Suppress("UNCHECKED_CAST")
        return (mapper.readValue(response, Map::class.java) as Map<String, Any>)["token"] as String
    }

    @Test
    fun `unauthenticated requests to job endpoints are rejected`() {
        mvc.perform(get("/api/jobs")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `a garbage token is rejected`() {
        mvc.perform(get("/api/jobs").header("Authorization", "Bearer not-a-real-token"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `register returns a usable token`() {
        val token = authenticate()
        assertTrue(token.split(".").size == 3, "a JWT has three dot-separated segments")

        mvc.perform(get("/api/jobs").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
    }

    @Test
    fun `registering a duplicate username returns 409`() {
        val username = "dupe-${System.nanoTime()}"
        val body = mapper.writeValueAsString(mapOf("username" to username, "password" to "password123"))

        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated)
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isConflict)
    }

    @Test
    fun `login with a wrong password returns 401`() {
        val username = "login-${System.nanoTime()}"
        mvc.perform(
            post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("username" to username, "password" to "password123")))
        ).andExpect(status().isCreated)

        mvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("username" to username, "password" to "wrongpassword")))
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `creating a job returns 201 and the job is retrievable`() {
        val token = authenticate()
        val body = mapper.writeValueAsString(mapOf("name" to "send-email", "payload" to """{"to":"a@b.com"}"""))

        val created = mvc.perform(
            post("/api/jobs").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(body)
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.name").value("send-email"))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.attempts").value(0))
            .andReturn().response.contentAsString

        @Suppress("UNCHECKED_CAST")
        val id = (mapper.readValue(created, Map::class.java) as Map<String, Any>)["id"] as String

        mvc.perform(get("/api/jobs/$id").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(id))
    }

    @Test
    fun `a blank job name is rejected with field-level detail`() {
        val token = authenticate()
        val body = mapper.writeValueAsString(mapOf("name" to "", "payload" to "x"))

        mvc.perform(
            post("/api/jobs").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(body)
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fields.name").exists())
    }

    @Test
    fun `maxAttempts outside the allowed range is rejected`() {
        val token = authenticate()
        val body = mapper.writeValueAsString(mapOf("name" to "x", "maxAttempts" to 99))

        mvc.perform(
            post("/api/jobs").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(body)
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fields.maxAttempts").exists())
    }

    @Test
    fun `unknown job id returns 404`() {
        val token = authenticate()
        mvc.perform(
            get("/api/jobs/${java.util.UUID.randomUUID()}").header("Authorization", "Bearer $token")
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `stats endpoint reports queue depth by status`() {
        val token = authenticate()
        repeat(3) {
            mvc.perform(
                post("/api/jobs").header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(mapOf("name" to "stat-job-$it")))
            ).andExpect(status().isCreated)
        }

        mvc.perform(get("/api/jobs/stats").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.pending").isNumber)
    }
}
