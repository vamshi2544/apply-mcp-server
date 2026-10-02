# Rebuild prompts

Three prompts that rebuild this project from an empty folder, for example on a work laptop where the code cannot be copied across. Run them in order, one per AI session (GitHub Copilot agent mode, Claude Code, or similar). Each prompt checks what already exists before it starts.

| Prompt | Builds | Done when |
|---|---|---|
| `01-server-and-mock.md` | Maven project, three MCP tools, mock backend, config | Server starts and MCP Inspector lists and calls the three tools |
| `02-real-backend-switch-and-tests.md` | Real HTTP backend, mock/real switch, IntelliJ run configs, 30 tests | `mvn test` passes; real mode without settings refuses to start |
| `03-docs.md` | README, CLAUDE.md, mcp.json example, `docs/index.html` handbook | Handbook opens in a browser with three diagrams |

How to use them:

1. Create an empty folder and open it in IntelliJ.
2. Paste the whole of prompt 1 into the AI assistant (agent mode, so it can create files and run commands).
3. When it reaches a line marked **STOP — ASK USER**, it will wait for you. Answer, then let it continue.
4. Run the checks at the end of the prompt yourself. Only move to the next prompt when they pass.
5. If something fails, paste the exact error back into the same session before moving on.

Before prompt 1 on a corporate machine, confirm:

- JDK 17 or later is installed (`java -version`).
- Maven can download from your internal mirror. The prompt checks this first, because Spring AI 1.1 artifacts are the most likely thing to be missing.
- You are working in a company repository, not a personal one.
