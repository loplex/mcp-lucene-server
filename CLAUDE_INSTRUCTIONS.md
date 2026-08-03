# INSTRUCTIONS FOR CLAUDE (MCP Lucene Integration)

You are equipped with a high-performance Apache Lucene search engine connected via Model Context Protocol (MCP).
Use the tool `search_code` anytime you need to locate files, identify structures, or perform complex context discoveries.

## How to execute searches:
1. Always prefer multi-field precise queries to minimize context window bloat.
2. Syntax rules you can exploit:
   - Specific extensions: `extension:kt`, `extension:ts`, `extension:py`
   - Class/Method lookup: `content:UserService`
   - Combined logic: `content:"jwt.verify" AND extension:ts AND -path:node_modules`
   - Proximity or Fuzzy match: `content:"auth validation"~3` or `content:initialise~1`

## Goal:
Keep your active session memory minimal. Run a query first, retrieve only the relevant snippets, and then ask for specific line blocks or targeted updates. Do not request whole codebase dumps.
