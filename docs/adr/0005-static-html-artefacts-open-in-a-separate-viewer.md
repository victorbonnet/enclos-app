---
status: proposed
---

# Static HTML artefacts open in a separate viewer

Amends [0002](0002-messages-are-markdown-drawn-by-the-app.md): message text keeps its native markdown rendering and literal HTML, while a separately opened HTML attachment may use a web view. Delivery and ownership remain enclos's existing file contract ([enclos ADR 0048](https://github.com/victorbonnet/enclos/blob/main/docs/adr/0048-artefacts-are-static-html-attachments.md), proposed).

Decision (proposed): a self-contained UTF-8 HTML attachment opens as an **artefact**, in its own full-screen viewer with Back, its name and Share. HTML, inline CSS and embedded data images or fonts are the rendering contract. The viewer disables JavaScript, storage, network loading, filesystem and content access, forms and outgoing navigation, and exposes no native bridge. The page receives none of the app's keys, connection or conversation state. Android uses a WebView with an in-memory document at a synthetic HTTPS address, a restrictive response Content Security Policy and refusal of every other resource request; iOS will need equivalent controls before offering the feature.

Reason: a platform web view renders a generated document's layout without building another HTML renderer. Keeping the page offline and separate from conversation text bounds what an agent's page can do; opening it in an external browser or publishing it would add a different access path. Interactive pages, external resources and multi-file sites are outside this first version. These are the intended controls; the spec's To verify list tracks the platform probes still needed.
