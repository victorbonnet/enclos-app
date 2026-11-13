---
status: proposed
---

# PDFs open in a native viewer with isolated rendering

An attachment tap should let the person read a document before deciding whether to share it. PDFs also introduce a complex parser into an app holding conversations and device keys.

Decision (proposed): PDFs open in a native, read-only viewer. Android uses AndroidX `PdfView` and [`SandboxedPdfLoader`](https://developer.android.com/reference/androidx/pdf/SandboxedPdfLoader) from `pdf-viewer` and `pdf-document-service` [1.0.0-beta01](https://developer.android.com/jetpack/androidx/releases/pdf), with platform PDF rendering behind a process boundary. The loader passes a file descriptor to its rendering service; the verified upstream manifest declares that service isolated and unexported. External PDF links and forms are disabled. Back, Share and an explicit Open in another app remain available when a document cannot be previewed. iOS needs a native PDF viewer when implemented; its rendering boundary is still to be chosen. Delivery, cache and ownership remain the existing attachment contract.

Reason: native reading keeps the ordinary open action inside the app without uploading the document or requiring another installed viewer. Rendering in a separate service keeps PDF parsing away from the app process. An external viewer remains a deliberate handoff; a cloud viewer would disclose the file, a WebView-based PDF reader adds a web execution surface, and a custom parser or renderer would add substantial document-format and rendering work. The trade-off is a beta AndroidX dependency and a service lifecycle to manage. Upstream API and final debug manifest review establish the configured boundary; the spec's To verify list tracks device probes still needed, rather than claiming runtime isolation has been demonstrated.

Opening a file in another app is limited to a fixed list of media types, each with its extensions, and goes through the system's chooser. The agent chooses a file's type and name, and the receiving app may take the type from the extension instead of the intent, so an open-anything handoff would let an agent have a browser run its XHTML or SVG, outside the artefact viewer's restrictions (ADR 0005), or have an installer receive its APK. Types off the list, or a name whose extension is not one of its type's, can only be shared, which hands over the file without asking an app to open it. The cost is that a legitimate but unlisted document needs Share rather than Open; the list can grow, but only with types no common handler runs as code.

The pinned beta loader's cancellation and service-cleanup behaviour is handled by the preview's resource owner. Dependency upgrades need a renewed lifecycle check, including dismissal before service connection and a document returned after cancellation.
