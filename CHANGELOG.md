<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Zk-client Changelog

## Unreleased

### Added

### Changed

### Deprecated

### Removed

### Fixed

### Security

## 1.0.0 - 2026-09-27

### Added

- ZooKeeper tool window with multi-connection management (persisted per project)
  — connect string with optional chroot, session timeout, digest auth, auto-reconnect.
- Lazy-loading znode tree with tooltips (size, children, mtime, version), speed search
  and live connection-state badges.
- Node details panel: data viewer/editor with optimistic versioning, JSON pretty-print,
  hex preview for binary payloads, and a full Stat table.
- Node operations: create (4 create modes, 3 ACL presets), recursive delete with
  confirmation, copy path, go-to-path navigation.
- Per-connection watch toggle that auto-refreshes the tree and the selected node
  on server-side changes.
- Balloon notifications for connection lifecycle and operation errors.
- Unit tests for path helpers and the JSON formatter.

### Changed (performance refactor for huge trees)

- Data/UI separation: pure `ZkNode`/`ZkConnectionNode` data tree (`ZkModel.kt`) with a
  pull-based `TreeModel` adapter (`ZkTreeModel.kt`) replacing `DefaultTreeModel` +
  `DefaultMutableTreeNode`.
- JTree runs in large-model mode with fixed row height — only expanded/visible rows
  are ever read from the model.
- Child lists over 2000 entries stream into the UI in 2000-row EDT batches
  (render while loading) instead of one blocking structure event.
- Watch refreshes now diff names and fire precise insert/remove events — loaded
  subtrees and expansion state survive; watch bursts are coalesced per path.
- Node memory roughly halved: lazy-cached paths, sorted lists with binary-search
  lookup (no per-node hash maps), shared empty list for childless leaves.
- Cache eviction: collapsing a node of a connection holding >20 000 loaded lists
  frees the lists below it; names stay cached and one level re-fetches per expand.

### Added (completeness pass)

- Recursive node search (`Search Nodes…`): cancellable background BFS under the
  selection with progress, capped at 500 matches, results popup navigates on click.
- ACL tab in the details pane (`getACL`, perms rendered as cdrwa).
- Keyboard shortcuts in the tree: INSERT create, DELETE delete, F5 refresh.
- "Test Connection" button in the connection dialog (8s connectivity probe).

### Fixed

- Watch toggle action is disabled without a selection.
- Deleting a node clears the details pane when a descendant of it is selected.
- Deleting an already-vanished node now reports "Already gone" instead of an error;
  the recursive-delete confirmation clarifies that child count is direct children only.
