/* MilzzyNodesMap Square Edition: non-destructive square bounds, grid, and square brush helpers. */
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  if (root) root.NodesSquare = api;
})(typeof window !== "undefined" ? window : null, function () {
  "use strict";
  /** Nodes chunks are 16x16 blocks; accept arrays of flat [cx,cz,...] coordinates. */
  function bounds(chunkArrays, paddingChunks = 8) {
    let minX = Infinity, maxX = -Infinity, minZ = Infinity, maxZ = -Infinity;
    for (const chunks of chunkArrays) {
      for (let i = 0; i + 1 < chunks.length; i += 2) {
        const x = chunks[i], z = chunks[i + 1];
        if (!Number.isSafeInteger(x) || !Number.isSafeInteger(z)) continue;
        minX = Math.min(minX, x); maxX = Math.max(maxX, x);
        minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
      }
    }
    if (!Number.isFinite(minX)) return null;
    const padding = Math.max(0, Math.floor(paddingChunks));
    const span = Math.max(maxX - minX + 1, maxZ - minZ + 1) + 2 * padding;
    // Round to a multiple of 16 chunks so each side fits the same square-sector grid.
    const sideChunks = Math.max(16, Math.ceil(span / 16) * 16);
    const x0 = Math.floor((minX + maxX + 1 - sideChunks) / 2);
    const z0 = Math.floor((minZ + maxZ + 1 - sideChunks) / 2);
    return { minX: x0 * 16, maxX: (x0 + sideChunks) * 16,
             minZ: z0 * 16, maxZ: (z0 + sideChunks) * 16, sideChunks };
  }
  /** Treat first chunk as an anchored corner; opposite corner always makes a real square. */
  function oppositeCorner(a, b) {
    const d = Math.max(Math.abs(b[0] - a[0]), Math.abs(b[1] - a[1]));
    return [a[0] + (b[0] < a[0] ? -d : d), a[1] + (b[1] < a[1] ? -d : d)];
  }
  /** A bounded number of world-grid lines (no massive pan-related SVG allocations). */
  function segments(frame, viewport, cellChunks = 16, maxLines = 120) {
    if (!frame || !viewport) return [];
    const x0 = Math.max(frame.minX, viewport.minX), x1 = Math.min(frame.maxX, viewport.maxX);
    const z0 = Math.max(frame.minZ, viewport.minZ), z1 = Math.min(frame.maxZ, viewport.maxZ);
    if (x1 < x0 || z1 < z0) return [];
    let step = Math.max(1, Math.floor(cellChunks)) * 16;
    while (Math.ceil((x1 - x0) / step) + Math.ceil((z1 - z0) / step) > maxLines) step *= 2;
    const out = [];
    for (let x = Math.ceil(x0 / step) * step; x <= x1; x += step) out.push([[x, z0], [x, z1]]);
    for (let z = Math.ceil(z0 / step) * step; z <= z1; z += step) out.push([[x0, z], [x1, z]]);
    return out;
  }
  return Object.freeze({ bounds, oppositeCorner, segments });
});
