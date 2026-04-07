# OkTrace 🔍

![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)
![Kotlin](https://img.shields.io/badge/kotlin-1.9.23-purple.svg)
![OkHttp](https://img.shields.io/badge/okhttp-4.x-green.svg)

> Per-interceptor request logger for OkHttp.  
> See exactly what each interceptor changes — not just the final request.

---

## The Problem

When you have multiple interceptors in your OkHttp client, 
debugging becomes painful. You have no visibility into which 
interceptor:

- Added, removed, or modified a header
- Changed the URL
- Changed the HTTP method
- Modified the request body

OkTrace solves this by showing you a detailed diff at every 
step of the chain.

---

## The Solution

OkTrace wraps each interceptor individually and shows a **diff at every step**:
```
┌──────────────────────────────────────────────────────
│ ▶  POST  https://api.example.com/v1/login
├──────────────────────────────────────────────────────
│  AuthInterceptor            1ms   [MODIFIED]
│    + Authorization: Bearer eyJhb...
│  HeaderInterceptor          0ms   [MODIFIED]
│    + X-App-Version: 1.0.0
│    + X-Platform: Android
│  SecurityInterceptor        0ms   [MODIFIED]
│    - X-Debug-Token: removed
│  NoOpInterceptor            0ms   [pass-through]
├──────────────────────────────────────────────────────
│ ◀  200 OK  •  Total: 243ms
│    Content-Type: application/json
│    Body: { "token": "eyJhb..." }
└──────────────────────────────────────────────────────
```

---

## Installation

Add JitPack to your `settings.gradle.kts`:
```kotlin
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }
    }
}
```

Add the dependency:
```kotlin
dependencies {
    implementation("com.github.NarendraKumar-2259:OkTrace:1.0.0")
}
```

---

## Usage
```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(OkTrace.interceptor())                 // always first
    .addInterceptor(OkTrace.observe(AuthInterceptor()))    // wrap each one
    .addInterceptor(OkTrace.observe(HeaderInterceptor()))
    .addInterceptor(OkTrace.observe(LoggingInterceptor()))
    .build()
```

---

## What OkTrace Detects

| Change | Symbol | Example |
|---|---|---|
| Header added | `+` | `+ Authorization: Bearer ...` |
| Header removed | `-` | `- X-Debug-Token` |
| Header changed | `~` | `~ Content-Type: application/json` |
| URL changed | `~` | `~ URL changed` |
| Body changed | `~` | `~ Body changed` |
| No changes | — | `[pass-through]` |

---

## Configuration
```kotlin
OkTrace.logTag        = "MyApp"  // change log tag (default: "OkTrace")
OkTrace.enabled       = false    // disable at runtime
TraceStore.maxEntries = 50       // limit memory usage (default: 100)
```

---

## Access Traces Programmatically
```kotlin
val traces = OkTrace.store.entries

traces.forEach { trace ->
    println("${trace.method} ${trace.url} — ${trace.totalDurationMs}ms")
    trace.spans.forEach { span ->
        println("  ${span.interceptorName}: ${span.diff.hasChanges}")
    }
}
```

---

## License

Copyright 2026 Narendra Kumar

Licensed under the Apache License, Version 2.0 — see [LICENSE](LICENSE) for details.
