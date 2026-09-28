# ZooKeeper Client (zk-client)

An IntelliJ Platform plugin that embeds a ZooKeeper client in the IDE:
browse znodes, inspect and edit node data, watch live changes — without leaving the editor.

## Features

- **Multiple connections** — saved per project (`zk-client.xml`), each with its own
  connect string (`host:port[,host:port...][/chroot]`), session timeout, optional
  digest auth, and auto-reconnect on session expiry.
- **Znode tree** — lazy-loading tree with per-node tooltips (data length, children,
  mtime, version), speed search (`TreeSpeedSearch`), and connection state in the root labels.
- **Data viewer / editor** — UTF-8 text editing with optimistic concurrency
  (`setData` with the observed version; a `BadVersion` conflict reloads the node),
  one-click JSON pretty-print, and a hex preview for binary payloads.
- **Stat inspection** — czxid/ctime, mzxid/mtime, data/child/ACL versions,
  ephemeral owner, pzxid, data length.
- **Node management** — create (persistent/ephemeral/sequential, ACL presets),
  delete with recursive confirmation, copy path, go-to-path navigation that
  loads each ancestor level on demand.
- **Recursive search** — background, cancellable name search under any subtree
  with a results popup (essential when speed search can only see expanded rows).
- **ACL viewer** — per-node `getACL` rendered as `scheme:id` + `cdrwa` permissions.
- **Keyboard** — INSERT creates, DELETE deletes, F5 refreshes the selected node.
- **Watches** — per-connection toggle; children/data watches registered on every read
  keep the tree and the selected node's details up to date automatically.
- **Non-blocking UI** — every ZooKeeper call runs on a worker thread; results are
  delivered back on the EDT. Errors surface as balloon notifications.

## Usage

1. Open the **ZooKeeper** tool window (right edge).
2. Click **+** to add a connection, e.g. `localhost:2181`.
3. Select the connection and press **Connect**, then expand the tree.
4. Select a node to view/edit its data on the right; use the toolbar or right-click
   the tree for the rest.

## Development

```
gradlew test          # unit tests
gradlew runIde        # sandbox IDE with the plugin
gradlew buildPlugin   # distributable zip in build/distributions
```

Requires JDK 21 (Gradle toolchain resolves it automatically via foojay).

## Documentation

- [Troubleshooting](docs/TROUBLESHOOTING.md) — connection issues, debug logging, huge-cluster tips
- [Future improvements](docs/FUTURE_IMPROVEMENTS.md) — ideas for connection UX and onboarding
- [Optimization summary](docs/OPTIMIZATION_SUMMARY.md) — notes from the 2026-09 code-quality pass

## Performance design (huge trees)

The plugin is built for very large znode trees (100k+ nodes under one parent):

- **Data/UI separation** — `ZkModel.kt` is a pure data tree with no Swing types;
  `ZkTreeModel` is a pull-based `TreeModel` adapter that only hands the JTree what
  is visible. The JTree runs in large-model mode with a fixed row height, so child
  access is lazy: only expanded & visible rows are ever requested.
- **Lightweight nodes** — a node stores its child *name* (not the absolute path;
  the path is derived and cached lazily), children are sorted for O(log n) lookup
  with no per-node hash indexes, and childless leaves share one empty list.
  Roughly half the per-node footprint of `DefaultMutableTreeNode`.
- **Render while loading** — child lists beyond 2000 entries are streamed into the
  UI in 2000-row batches scheduled on the EDT, so the tree keeps painting while a
  huge `getChildren` result is being applied.
- **Precise watch updates** — refreshes compute a name diff and fire only
  insert/remove events, so loaded subtrees and expansion state survive; no
  subtree rebuilds on watch events. Watch bursts are coalesced per path.
- **Cache eviction** — collapsing a connection/node whose connection holds more
  than 20 000 loaded lists frees the loaded lists below it (names stay cached,
  one level is re-fetched per re-expand).

## Plugin structure

```
src/main/kotlin/wiki/twom/plugin/zk/
├── ZkPaths.kt            path helpers (pure, unit-tested)
├── JsonFormat.kt         dependency-free JSON pretty-printer (pure, unit-tested)
├── ZkModel.kt            pure data tree: ZkNode/ZkConnectionNode, diff, time (unit-tested)
├── ZkTreeModel.kt        pull-based TreeModel adapter: streaming, precise diff events, eviction
├── ZkConnection.kt       connection configs + project-level persistence
├── ZkSession.kt          ZooKeeper client wrapper: state machine + events
├── ZkSessionManager.kt   worker pool, session registry, EDT dispatch
├── ZkPanel.kt            tool window content: toolbar + tree + details wiring
├── ZkDetailsPanel.kt     data editor + stat table
├── ZkActions.kt          toolbar / popup actions
├── ZkDialogs.kt          connection, create-node, go-to dialogs
└── ZkToolWindowFactory.kt
```

Notes:

- The ZooKeeper client is bundled with the plugin; server-only transitive deps
  (jetty, netty, snappy, metrics) are excluded — the classic NIO client is used.
- Digest passwords are stored in plain project config today; use throwaway
  credentials for real clusters.
