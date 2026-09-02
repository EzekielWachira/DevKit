package io.devkit.netdemo

import io.devkit.netkit.scenario.MalformedResponseType
import io.devkit.netkit.scenario.SequenceCompletionBehavior
import io.devkit.netkit.scenario.TimeoutType
import io.devkit.netkit.scenario.pack.BuiltInScenarioPack
import io.devkit.netkit.scenario.pack.scenarioPack

/**
 * The demo's built-in scenario pack, declared in application code.
 *
 * This is the shape a real application would use: scenarios that describe *this*
 * API's failure modes, registered at startup, available to every QA engineer on
 * every build without anyone having to configure them by hand.
 *
 * Built-ins are code rather than data. They are re-declared on every launch and
 * never written to the scenario store, so changing one here changes what QA sees
 * on the next build instead of leaving a stale saved copy behind. A QA engineer
 * who wants to change one duplicates it into an ordinary editable scenario.
 */
internal object DemoScenarioPacks {

    /** Every pack this demo registers with NetKit. */
    val all: List<BuiltInScenarioPack> get() = listOf(demoApi, checkout)

    /**
     * One scenario per NetKit 0.2 capability, so the demo doubles as a manual
     * test plan for the toolkit itself.
     */
    private val demoApi = scenarioPack("Demo API") {
        description("One scenario per NetKit capability, against the demo's own endpoints.")

        scenario("Server error") {
            description("GET /api/v1/bookings returns a backend-shaped 500.")
            get("/api/v1/bookings") {
                name("Bookings failure")
                respond(
                    statusCode = 500,
                    body = """{"message":"Bookings service temporarily unavailable"}""",
                )
            }
        }

        scenario("Slow response") {
            description("Everything takes 2.5 seconds — the loading-state scenario.")
            latency(2_500)
        }

        scenario("Malformed JSON") {
            description("A 200 whose body stops mid-object, to exercise the parser's error path.")
            get("/api/v1/bookings") {
                name("Truncated bookings")
                malformed(MalformedResponseType.TruncatedJson)
            }
        }

        scenario("Retry eventually succeeds") {
            description(
                "Two failures then a success. Call the endpoint three times and watch the " +
                    "sequence indicator advance.",
            )
            get("/api/v1/bookings") {
                name("Bookings retry")
                sequence {
                    respond(500, body = """{"message":"Try again"}""")
                    respond(500, body = """{"message":"Try again"}""")
                    respond(
                        200,
                        body = """{"bookings":[{"id":"b-1","service":"Plumbing","status":"confirmed"}]}""",
                    )
                }
            }
        }

        scenario("Rate limited") {
            description("A 429 with Retry-After, for testing backoff and retry-after handling.")
            get("/api/v1/notifications") {
                name("Notifications rate limit")
                respond(
                    statusCode = 429,
                    body = """{"message":"Too many requests"}""",
                    headers = listOf(
                        io.devkit.netkit.scenario.ResponseHeader("Retry-After", "60"),
                        io.devkit.netkit.scenario.ResponseHeader("X-RateLimit-Remaining", "0"),
                    ),
                )
            }
        }

        scenario("Empty state") {
            description("A perfectly valid 200 with nothing in it — the empty-list screen.")
            get("/api/v1/bookings") {
                name("No bookings")
                respond(200, body = """{"bookings":[]}""")
            }
        }

        scenario("Offline except profile") {
            description(
                "Everything fails as offline while /api/v1/profile keeps working, which is " +
                    "what a pass-through rule is for.",
            )
            offline()
            get("/api/v1/profile") {
                name("Profile allow-list")
                passThrough()
            }
        }
    }

    /** A pack organised around one feature rather than one capability. */
    private val checkout = scenarioPack("Checkout") {
        description("Everything that can go wrong at payment time.")

        scenario("Checkout timeout") {
            description("POST /api/v1/checkout never answers.")
            post("/api/v1/checkout") {
                name("Checkout timeout")
                timeout(TimeoutType.READ)
            }
        }

        scenario("Gateway unavailable") {
            description("The payment gateway is down: a 503 with a Retry-After.")
            post("/api/v1/checkout") {
                name("Gateway unavailable")
                respond(
                    statusCode = 503,
                    body = """{"message":"Payment gateway unavailable"}""",
                    headers = listOf(
                        io.devkit.netkit.scenario.ResponseHeader("Retry-After", "30"),
                    ),
                )
            }
        }

        scenario("Payment declined") {
            description("A 402 the app should present as a user-facing decline, not an error.")
            post("/api/v1/checkout") {
                name("Payment declined")
                respond(
                    statusCode = 402,
                    body = """{"code":"card_declined","message":"Your card was declined"}""",
                )
            }
        }

        scenario("Flaky checkout") {
            description(
                "Timeout, then success — the classic double-charge risk. Submit twice and " +
                    "check the app does not create two orders.",
            )
            post("/api/v1/checkout") {
                name("Flaky checkout")
                sequence(SequenceCompletionBehavior.REPEAT_LAST) {
                    timeout(TimeoutType.READ)
                    respond(201, body = """{"orderId":"o-1042","total":"KES 3,500"}""")
                }
            }
        }
    }
}
