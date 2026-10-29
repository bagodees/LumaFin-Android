package io.github.bagodees.lumafin.mobile.telemetry

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

class CrashServerTests : FunSpec({
	test("server addresses keep their scheme but lose the host") {
		CrashServer.scrub("GET https://jarvis.example.com:8920/Items/abc/PlaybackInfo") shouldBe
			"GET https://[server]/Items/abc/PlaybackInfo"
		CrashServer.scrub("socket wss://media.example.org/socket") shouldBe "socket wss://[server]/socket"
	}
	test("tokens and keys in query strings and headers are removed") {
		val scrubbed = CrashServer.scrub(
			"url ?api_key=0123456789abcdef&static=true X-Emby-Token: deadbeef Authorization: MediaBrowser Token=\"secret\""
		)
		scrubbed shouldNotContain "0123456789abcdef"
		scrubbed shouldNotContain "deadbeef"
		scrubbed shouldNotContain "secret"
	}
	test("IP addresses are removed") {
		CrashServer.scrub("connect to 192.168.1.20 failed") shouldBe "connect to [ip] failed"
	}
	test("stack traces stay readable") {
		val line = "\tat io.github.bagodees.lumafin.mobile.ui.player.PlayerActivity.onCreate(PlayerActivity.kt:88)"
		CrashServer.scrub(line) shouldBe line
	}
	test("the DSN becomes the crash server's store address") {
		CrashServer.storeUrl("https://abc123@crash.example.com/2") shouldBe "https://crash.example.com/api/2/store/"
		CrashServer.storeUrl("http://abc123@crash.example.com:8000/2") shouldBe "http://crash.example.com:8000/api/2/store/"
	}
	test("no DSN, or one without a key or project, sends nothing") {
		CrashServer.storeUrl("") shouldBe null
		CrashServer.storeUrl("https://crash.example.com/2") shouldBe null
		CrashServer.storeUrl("https://abc123@crash.example.com/") shouldBe null
	}
	test("only alpha and beta builds report") {
		CrashServer.isTestVersion("1.2.0-alpha.3") shouldBe true
		CrashServer.isTestVersion("1.2.0-alpha.3-debug") shouldBe true
		CrashServer.isTestVersion("1.3.0-beta.1") shouldBe true
		CrashServer.isTestVersion("1.2.0") shouldBe false
	}
})
