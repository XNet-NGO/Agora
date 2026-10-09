# Agora Manual

Agora is a BYOK (bring-your-own-key) LLM client for Android with multi-provider access,
agentic workflows, and remote device control. It stores conversations locally, sends model
requests directly to the provider you choose, and supports non-linear message branches,
Context Compact, MCP servers, automation, search, memory, local models, and remote shell tools.

## Providers

Agora supports ten built-in provider types: OpenAI, Anthropic, Google Gemini, DeepSeek,
Qwen/DashScope, OpenRouter, OpenCode Go, Groq, Ollama, and Local llama.cpp. Custom endpoints
support OpenAI-compatible, Google, or Anthropic protocols. Bring your own key for any of them.

## Conversations

Conversations form a tree through parent IDs. You can edit or regenerate earlier messages
without discarding alternative branches. Agora uses token-budget context (4K-1M estimated
tokens) with non-destructive Compact capsules that retain a verbatim recent suffix.

## Tools

Agora exposes agentic tools: web search, memory, past-conversation RAG, image generation,
MCP servers, Tasks and Loops automation, remote shell and file operations with durable Conch
jobs, and an F-Droid Alpine sandbox. Tool calls and results become part of the conversation
protocol and can be sent to the selected model on subsequent passes.

## Local intelligence

Agora can run GGUF chat models and local embeddings through llama.cpp, so you can work fully
offline with no cloud provider.

## Data portability

Agora supports versioned `.agora` ZIP archives, ChatGPT and Claude imports, and scheduled
backups.

## Privacy

Agora does not relay chat completions or run general analytics. Conversations remain in
app-managed local storage. Configured providers and tools are contacted directly when used.
Secret settings use an Android Keystore AES-GCM envelope by default.