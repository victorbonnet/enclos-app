---
status: proposed
---

# An agent's messages are markdown, drawn by the app

Agents answer in markdown: language models write `**bold**`, lists, code and tables by habit, and a runtime is told what a channel renders (Hermes: a platform plugin's `platform_hint`). Shown as text, a reply is full of marks; rendered as HTML, a message could load images from anywhere, which tells their server the phone's address and when the message was read, or run what it carries.

Decision (proposed): an agent's message is **CommonMark with GitHub's tables, strikethrough and bare links**, and each app **parses it and draws it with its own components** (`docs/spec.md`, Messages), never through HTML or a web view. Nothing in a message is fetched or run: an image shows as a link with its alt text, HTML as its literal text, and only `http`, `https` and `mailto` links open on a tap. HTTP(S) links use the browser's Custom Tabs on Android; `mailto` uses the system mail handler ([ADR 0005](0005-static-html-artefacts-open-in-a-separate-viewer.md)). The person's own messages are not markdown. The contract between the platforms is that dialect and the spec's table of what each element looks like, not a library: each platform uses a CommonMark parser of its own (commonmark-java on Android, Apple's swift-markdown on iOS).

Reason: drawing the elements natively keeps the design's tokens and the platform's text handling (selection, accessibility, dark mode), and it leaves nothing for a message to load or execute. CommonMark and the GitHub extensions are specified, so two conforming parsers read a message the same way.

## Considered options

- **A web view** rendering HTML: every element for free, but remote images, scripts to disable one by one, and text that is not the platform's.
- **Parsing in the Rust core**, the same tree sent to both apps: one parser, but a markdown tree to export through UniFFI for a dialect that is already specified. To revisit if the two platforms' parsers are seen to disagree.

## Consequences

- Separately opened HTML attachments follow [ADR 0005](0005-static-html-artefacts-open-in-a-separate-viewer.md); this decision still governs message text.
- Each runtime's plugin tells the agent which elements the app draws (in enclos), so the hint and this list change together.
- An agent's literal `*`, `_`, `#` or `|` can format text, as in any chat app that renders markdown.
