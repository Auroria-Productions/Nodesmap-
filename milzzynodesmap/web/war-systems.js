/* MilzzyNodesMap public VIEWER: configuration-aware war UI adapter.
 * Supports old live.json exports and the new war-systems.json bridge.
 * Nothing here modifies the editor or the server's gameplay rules.
 */
(function (root) {
  'use strict';
  const ids = ['flagWar', 'nodesWar', 'keyPointWars', 'warzones', 'raids'];
  const names = { flagWar: 'Flag War', nodesWar: 'NodesWar', keyPointWars: 'Key-point Wars', warzones: 'Warzones', raids: 'Raids' };
  const aliases = {
    flagWar: ['flagWar', 'flag', 'flags', 'classic', 'legacy'],
    nodesWar: ['nodesWar', 'nodeswar', 'conquest', 'territoryWar'],
    keyPointWars: ['keyPointWars', 'keypointWars', 'keyPointWar', 'keypointWar', 'keypoints', 'keyPoints', 'strongholdWar'],
    warzones: ['warzones', 'warzone'], raids: ['raids', 'raid'],
  };
  const obj = (v) => v && typeof v === 'object' && !Array.isArray(v) ? v : {};
  const arr = (v) => Array.isArray(v) ? v : [];
  const bool = (v) => typeof v === 'boolean' ? v : v === 'true' ? true : v === 'false' ? false : undefined;
  function locate(map, name) {
    if (Array.isArray(map)) {
      return map.find(item => aliases[name].some(a => String(item?.id ?? item?.type ?? '').toLowerCase() === a.toLowerCase()));
    }
    for (const alias of aliases[name]) if (Object.prototype.hasOwnProperty.call(obj(map), alias)) return map[alias];
    return undefined;
  }
  function records(value) {
    if (Array.isArray(value)) return value;
    const v = obj(value);
    for (const key of ['battles', 'keypoints', 'keyPoints', 'keyPointBattles', 'activeBattles',
      'ongoingBattles', 'wars', 'events', 'active', 'sessions']) {
      if (Array.isArray(v[key])) return v[key];
      if (v[key] && typeof v[key] === 'object')
        return Object.entries(v[key]).filter(([, item]) => item && typeof item === 'object')
          .map(([id, item]) => ({ id, ...item }));
    }
    if (Object.keys(v).length && Object.values(v).every(item => item && typeof item === 'object'))
      return Object.entries(v).map(([id, item]) => ({ id, ...item }));
    return [];
  }
  function resolve(liveInput, publisherInput) {
    const live = obj(liveInput);
    const source = obj(publisherInput);
    const cfg = obj(source.config);
    const publishedSystems = source.systems || cfg.systems;
    const result = {};
    const currentWar = obj(live.war);
    for (const id of ids) {
      const published = locate(publishedSystems, id);
      const entry = obj(published);
      let enabled = bool(published);
      if (enabled === undefined) enabled = bool(entry.enabled);
      if (enabled === undefined) enabled = bool(locate(cfg, id));
      if (enabled === undefined) enabled = bool(obj(locate(cfg, id)).enabled);
      // Backwards compatibility only when the server has no authoritative config snapshot.
      if (enabled === undefined) {
        if (id === 'flagWar') enabled = true;
        else if (id === 'warzones') enabled = true;
        else if (id === 'raids') enabled = true;
        else enabled = Boolean(locate(live, id));
      }
      if (cfg.enabled === false) enabled = false;
      let battles = [];
      if (id === 'flagWar') battles = arr(live.attacks);
      if (id === 'warzones') battles = arr(live.warzones).filter(z => z && z.enabled !== false);
      if (id === 'raids') battles = arr(live.raids);
      if (id === 'keyPointWars' || id === 'nodesWar') {
        const fields = id === 'keyPointWars'
          ? ['keyPointBattles', 'keypointBattles', 'keyPoints', 'keypoints', 'keyPointWars']
          : ['nodesWarBattles', 'nodeswarBattles', 'nodesWars', 'nodesWar'];
        for (const key of fields) {
          battles = records(source[key]); if (battles.length) break;
          battles = records(live[key]); if (battles.length) break;
        }
        if (!battles.length) battles = records(entry);
      }
      const explicitActive = bool(entry.active);
      const active = enabled && (id === 'flagWar'
        ? Boolean(currentWar.enabled || battles.length)
        : explicitActive === undefined ? battles.some(b => {
            const phase = String(b?.status ?? b?.phase ?? '').toLowerCase();
            return !['ended', 'finished', 'cancelled', 'resolved', 'peace',
              'pending', 'scheduled', 'setup', 'draft', 'completed'].includes(phase);
          }) : explicitActive);
      result[id] = { id, title: String(entry.title || names[id]), enabled: Boolean(enabled), active: Boolean(active), battles };
    }
    return result;
  }
  function counts(systems) {
    const enabled = ids.filter(id => systems[id].enabled);
    const active = enabled.filter(id => systems[id].active);
    return { enabled: enabled.length, active: active.length, enabledIds: enabled, activeIds: active };
  }
  root.NodesWarSystems = Object.freeze({ resolve, counts, ids });
})(typeof window === 'undefined' ? globalThis : window);
