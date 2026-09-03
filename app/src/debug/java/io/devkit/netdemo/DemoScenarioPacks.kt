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
    val all: List<BuiltInScenarioPack>
        get() = listOf(demoApi, checkout, networkReliability, authentication, pagination)

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

    // ---- 0.3 ------------------------------------------------------------------

    /**
     * Chaos, at three intensities.
     *
     * Every one of these is reproducible: activate it, note the seed on the Run
     * tab, and the same sequence of failures comes back on a restart. That is the
     * difference between "the app is flaky on bad networks" and a bug report.
     *
     * Each excludes the demo's own refresh endpoint, which is the habit worth
     * demonstrating: chaos across everything also breaks the machinery you need
     * in order to watch chaos.
     */
    private val networkReliability = scenarioPack("Network Reliability") {
        description("Application-layer instability, deterministic under a seed.")

        scenario("Mild latency") {
            description("A little slowness and the occasional 500. Barely noticeable.")
            chaos {
                failurePercent(2)
                latency(200, 800)
                exclude("/api/v1/auth/refresh")
                outcomes {
                    http(500, weight = 2)
                    http(503, weight = 1)
                }
            }
        }

        scenario("Poor mobile network") {
            description(
                "Slow and unreliable. The one most client bugs actually hide behind — run " +
                    "it, note the seed, and every failure it produces is reproducible.",
            )
            chaos {
                failurePercent(8)
                latency(800, 3_000)
                exclude("/api/v1/auth/refresh")
                outcomes {
                    timeout(weight = 3)
                    http(503, weight = 3)
                    disconnect(weight = 2)
                }
            }
        }

        scenario("Unstable API") {
            description("Server errors only, no added latency. Isolates retry behaviour.")
            chaos {
                failurePercent(15)
                exclude("/api/v1/auth/refresh")
                outcomes {
                    http(500, weight = 1)
                    http(502, weight = 1)
                    http(503, weight = 1)
                }
            }
        }

        scenario("Heavy failure mode") {
            description("One request in four fails. Expect the app to struggle.")
            chaos {
                failurePercent(25)
                latency(500, 5_000)
                exclude("/api/v1/auth/refresh")
                outcomes {
                    http(500, weight = 3)
                    http(503, weight = 3)
                    timeout(weight = 2)
                    disconnect(weight = 2)
                }
            }
        }

        scenario("Checkout is a coin flip") {
            description(
                "Half of all checkouts fail, everything else is fine. Shows a probability " +
                    "on a single rule rather than chaos across the app.",
            )
            post("/api/v1/checkout") {
                name("Checkout coin flip")
                probabilityPercent(50)
                respond(500, body = """{"message":"Payment processor unavailable"}""")
            }
        }

        scenario("Weighted checkout outcomes") {
            description(
                "60% fine, and the rest a mix of failures. One rule, five branches, all " +
                    "reproducible from the seed.",
            )
            post("/api/v1/checkout") {
                name("Checkout outcomes")
                outcomes {
                    passThrough(weight = 60)
                    http(500, weight = 15)
                    http(503, weight = 10)
                    timeout(weight = 10)
                    disconnect(weight = 5)
                }
            }
        }
    }

    /**
     * The authentication edge cases, built the way the presets build them.
     *
     * Declared here in the DSL rather than generated, so the pack doubles as a
     * worked example of what a preset produces — the rules below are exactly what
     * "Access token expires" writes into an editable scenario.
     */
    private val authentication = scenarioPack("Authentication") {
        description("Token expiry, refresh, and the races around them.")

        scenario("Token expires once") {
            description("The first protected call returns 401. Refresh is left alone.")
            any("/api/v1/auth/refresh") {
                name("Refresh (excluded from 401s)")
                passThrough()
            }
            prefix("/api/v1") {
                name("Protected paths → 401 once")
                firstRequestOnly()
                respond(401, body = """{"error":"invalid_token"}""")
            }
        }

        scenario("Refresh succeeds") {
            description("401, then a refresh that works, then normal service.")
            post("/api/v1/auth/refresh") {
                name("Refresh succeeds")
                respond(
                    200,
                    body = """{"access_token":"demo-token-2","token_type":"Bearer"}""",
                )
            }
            prefix("/api/v1") {
                name("Protected paths → 401 once")
                firstRequestOnly()
                respond(401, body = """{"error":"invalid_token"}""")
            }
        }

        scenario("Refresh fails") {
            description(
                "The session is over. Check the app logs out exactly once and does not loop.",
            )
            post("/api/v1/auth/refresh") {
                name("Refresh rejected")
                respond(401, body = """{"error":"invalid_grant"}""")
            }
            prefix("/api/v1") {
                name("Protected paths → 401")
                respond(401, body = """{"error":"invalid_token"}""")
            }
        }

        scenario("Refresh timeout") {
            description(
                "Refresh never answers. Exposes UI deadlocks and duplicate logout events.",
            )
            post("/api/v1/auth/refresh") {
                name("Refresh times out")
                timeout(TimeoutType.READ)
            }
            prefix("/api/v1") {
                name("Protected paths → 401")
                respond(401, body = """{"error":"invalid_token"}""")
            }
        }

        scenario("Concurrent 401 storm") {
            description(
                "Every protected call fails at once while a slow refresh runs. Tap \"Call " +
                    "all endpoints\" and count the refresh requests in history — there " +
                    "should be one.",
            )
            post("/api/v1/auth/refresh") {
                name("Refresh (slow)")
                respond(
                    200,
                    body = """{"access_token":"demo-token-2","token_type":"Bearer"}""",
                    delayMillis = 2_000,
                )
            }
            prefix("/api/v1") {
                name("Protected paths → 401 until refreshed")
                sequence(SequenceCompletionBehavior.PASS_THROUGH) {
                    respond(401, body = """{"error":"invalid_token"}""")
                    respond(401, body = """{"error":"invalid_token"}""")
                    respond(401, body = """{"error":"invalid_token"}""")
                    respond(401, body = """{"error":"invalid_token"}""")
                    passThrough()
                }
            }
        }
    }

    /**
     * Pagination, against the demo's three real pages of `/api/v1/services`.
     *
     * Every one of these targets **page 2** with a query condition, so page 1 and
     * page 3 keep coming from the backend. That contrast is the demonstration: a
     * scenario that replaced every page would show nothing an "always fails" rule
     * could not.
     */
    private val pagination = scenarioPack("Pagination") {
        description("Failures that only happen on the second page.")

        scenario("Page 2 fails") {
            description("Load page 1, then page 2. Only the second one errors.")
            get("/api/v1/services") {
                name("page=2 → 500")
                whereQuery("page", "2")
                respond(500, body = """{"error":"Simulated pagination failure"}""")
            }
        }

        scenario("Page 2 is slow") {
            description(
                "Page 2 takes five seconds and then returns real data. Watch the append " +
                    "loader and check the app does not request it twice.",
            )
            get("/api/v1/services") {
                name("page=2 → delayed")
                whereQuery("page", "2")
                delay(5_000)
            }
        }

        scenario("Empty next page") {
            description("Page 2 comes back with no items — the end-of-list case.")
            get("/api/v1/services") {
                name("page=2 → empty")
                whereQuery("page", "2")
                respond(200, body = """{"data":[],"next_page":null,"has_more":false}""")
            }
        }

        scenario("Page 2 retries and succeeds") {
            description(
                "Page 2 fails twice, then loads from the real backend. Tap it three times.",
            )
            get("/api/v1/services") {
                name("page=2 → retry twice")
                whereQuery("page", "2")
                sequence(SequenceCompletionBehavior.PASS_THROUGH) {
                    respond(500, body = """{"error":"Try again"}""")
                    respond(500, body = """{"error":"Try again"}""")
                    passThrough()
                }
            }
        }

        scenario("Duplicate page data") {
            description(
                "Page 2 returns page 1's ids. Check the list does not show each service twice.",
            )
            get("/api/v1/services") {
                name("page=2 → duplicate ids")
                whereQuery("page", "2")
                respond(
                    200,
                    body = """{"data":[{"id":"s-1","name":"Service 1"},""" +
                        """{"id":"s-2","name":"Service 2"}],"next_page":3,"has_more":true}""",
                )
            }
        }

        scenario("Malformed pagination metadata") {
            description(
                "No items, but the API says there are more pages and points back at itself. " +
                    "Check the app stops rather than looping forever.",
            )
            get("/api/v1/services") {
                name("page=2 → metadata that lies")
                whereQuery("page", "2")
                respond(
                    200,
                    body = """{"data":[],"next_page":2,"next_cursor":"same-as-current",""" +
                        """"has_more":true}""",
                )
            }
        }
    }
}
