# AI Code Review Assistant 🤖

An AI-powered code review tool that integrates with GitHub pull requests. It reads PR diffs, understands them *structurally* using AST parsing (not just line-by-line text), sends relevant context to an LLM, and posts inline review comments — like a human reviewer would.

**Built entirely in Java 21.** Packaged as a GitHub Action. **$0 cost** to run.

---

## ✨ Features

- **Structural code understanding** — Uses [JavaParser](https://javaparser.org/) to build an AST with symbol resolution, identifying *which methods changed* (not just which lines)
- **Context-aware reviews** — Follows imports and class hierarchy to give the LLM cross-file context, not just the isolated diff
- **Inline PR comments** — Posts findings directly on the relevant diff lines, exactly like a human reviewer
- **Severity-based review events** — Critical/Warning findings trigger `REQUEST_CHANGES`; Suggestions/Nitpicks post as `COMMENT`
- **Dual LLM failover** — Gemini (primary) with Groq fallback, so a rate limit on one doesn't kill the review
- **Virtual threads** — Reviews multiple files concurrently using Java 21 virtual threads
- **Fork-safe** — Gracefully falls back to GitHub Actions Job Summary when `GITHUB_TOKEN` is read-only (fork PRs)

## 🏗 Architecture

```
PR Opened/Updated
       │
       ▼
┌─────────────────┐
│  GitHub Action   │  ← Triggered by pull_request event
│  (Docker)        │
└────────┬────────┘
         │
         ▼
┌─────────────────┐     ┌──────────────┐
│ PullRequestService│────▶│ GitHub API    │  Fetch changed files + diffs
└────────┬────────┘     └──────────────┘
         │
         ▼
┌─────────────────┐
│  DiffParser      │  Parse unified diffs → structured hunks
│  AstAnalyzer     │  JavaParser AST + symbol resolution
│  ContextBuilder  │  Follow imports, hierarchy → related code
└────────┬────────┘
         │
         ▼
┌─────────────────┐     ┌──────────────┐
│  LlmRouter       │────▶│ Gemini API   │  Primary (free tier)
│  PromptBuilder   │    ┌┤ Groq API     │  Fallback (free tier)
│  ResponseParser  │    │└──────────────┘
└────────┬────────┘    │
         │◀────────────┘
         ▼
┌─────────────────┐     ┌──────────────┐
│ ReviewPublisher  │────▶│ GitHub API    │  Post inline comments + summary
└─────────────────┘     └──────────────┘
```

## 🚀 Quick Start

### 1. Add the workflow to your repository

Create `.github/workflows/ai-review.yml`:

```yaml
name: AI Code Review
on:
  pull_request:
    types: [opened, synchronize]

permissions:
  contents: read
  pull-requests: write

jobs:
  ai-review:
    runs-on: ubuntu-latest
    if: "!github.event.pull_request.draft"
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0
      - uses: your-username/ai-code-review-assistant@v1
        with:
          gemini_api_key: ${{ secrets.GEMINI_API_KEY }}
          groq_api_key: ${{ secrets.GROQ_API_KEY }}  # optional
```

### 2. Add your API keys as repository secrets

1. Get a free Gemini API key from [AI Studio](https://aistudio.google.com/apikey)
2. (Optional) Get a free Groq API key from [Groq Console](https://console.groq.com/)
3. Go to your repo → **Settings** → **Secrets and variables** → **Actions**
4. Add `GEMINI_API_KEY` (and optionally `GROQ_API_KEY`)

### 3. Open a PR

That's it. The AI reviewer will automatically post comments on your PR.

## ⚙️ Configuration

| Input | Description | Default |
|-------|-------------|---------|
| `gemini_api_key` | Google Gemini API key (required) | — |
| `groq_api_key` | Groq API key for fallback | — |
| `review_language` | Language to review | `java` |
| `severity_threshold` | Minimum severity to post (`CRITICAL`, `WARNING`, `SUGGESTION`, `NITPICK`) | `SUGGESTION` |
| `max_files` | Max files to review per PR | `15` |

## 🧱 Tech Stack

| Component | Technology | Why |
|-----------|-----------|-----|
| Language | Java 21 | Virtual threads, Oracle/JVM engineering story |
| AST Parsing | JavaParser 3.26 | Native Java AST with symbol resolution |
| GitHub API | github-api 1.326 | Standard Java GitHub REST client |
| LLM (primary) | Gemini Flash | Free tier, ~1M token context |
| LLM (fallback) | Groq (Llama 3.3) | Free tier, fast inference |
| HTTP Client | `java.net.http.HttpClient` | Built into JDK, zero dependencies |
| JSON | Jackson | Industry standard, records support |
| Build | Maven + Docker | Uber-JAR → multi-stage Docker image |
| Runtime | GitHub Actions | Free on public repos, zero hosting cost |

## 🏗 Building from Source

```bash
# Prerequisites: Java 21+, Maven 3.9+

# Build and run tests
mvn clean verify

# Build the Docker image
docker build -t ai-code-review-assistant .
```

## 📁 Project Structure

```
src/main/java/dev/codereviewer/
├── App.java                    # Entry point — wires everything together
├── config/
│   └── ReviewConfig.java       # Typed config from env vars
├── github/
│   ├── GitHubClientFactory.java
│   ├── PullRequestService.java # Fetch PR diffs and files
│   └── ReviewPublisher.java    # Post inline comments
├── parser/
│   ├── DiffParser.java         # Unified diff → structured hunks
│   ├── AstAnalyzer.java        # JavaParser AST + symbol resolution
│   └── CodeContext.java        # Structural analysis result
├── context/
│   ├── ContextBuilder.java     # Cross-file context enrichment
│   └── ContextChunker.java     # Fit context to token limits
├── llm/
│   ├── LlmClient.java          # Provider interface
│   ├── GeminiClient.java        # Gemini REST client
│   ├── GroqClient.java          # Groq REST client (OpenAI-compat)
│   ├── LlmRouter.java           # Primary → fallback routing
│   ├── PromptBuilder.java       # Prompt construction
│   ├── ResponseParser.java      # Parse LLM JSON output
│   └── ReviewFinding.java       # Finding data model
├── review/
│   ├── ReviewOrchestrator.java  # End-to-end pipeline
│   ├── FileReviewTask.java      # Per-file review (virtual thread)
│   └── ReviewResult.java        # Aggregated results
└── util/
    ├── JsonUtil.java
    ├── RetryUtil.java
    └── TokenEstimator.java
```

## 📜 License

MIT

## 🙏 Acknowledgements

Built as a portfolio project targeting Oracle R&D internship track. The Java/JVM-native approach (JavaParser symbol resolution, virtual threads, potential GraalVM Native Image) is a deliberate architectural choice aligned with Oracle's technology ecosystem.
