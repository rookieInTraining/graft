(el, opts) => {
  // Derives a uniqueness-checked locator for `el`: {within: string[], kind, value} or null.
  // Framework-neutral (Playwright evaluate / Selenium executeScript). `within` lists the
  // `shadow=<css>` hops of the open shadow roots `el` sits in, outermost first.
  // Tier order: own stable attribute, path from a stable ancestor anchor, unique short text,
  // structural path from the root (:root / shadow top-level child), else null.
  // opts.cssOnlyInShadow: skip the text tier inside shadow roots (Selenium's text locator is XPath).
  opts = opts || {};

  // Attributes usable as tier values / anchors, in priority order.
  const STABLE = ['data-testid', 'data-test', 'id', 'name', 'aria-label'];
  // Step qualifiers for the anchored path; the first present one is used.
  const QUALIFIERS = ['name', 'type', 'role'];
  // Generated-looking values (React ids, ember ids, CSS-in-JS classes, long numbers).
  const NOISY = /^[:\d]|\d{4,}|^(css|ember|react|mui|sc)-/i;
  const MAX_TEXT = 60;

  const isShadowRoot = (root) => root.nodeType === 11 && !!root.host;
  const isDocument = (root) => root.nodeType === 9;

  // `"value"` with `"` and `\` escaped (CSS string syntax); newlines as `\a `.
  const quote = (v) => '"' + v.replace(/["\\]/g, '\\$&').replace(/\r\n|\r|\n|\f/g, '\\a ') + '"';
  const attrCss = (name, v) => '[' + name + '=' + quote(v) + ']';

  // A usable attribute value, or null when absent, blank or noisy.
  const stableValue = (node, name) => {
    const v = node.getAttribute(name);
    return v && v.trim() && !NOISY.test(v) ? v : null;
  };

  // True when `css` matches exactly one node in `root`, and that node is `target`.
  const uniqueIn = (root, css, target) => {
    try {
      const found = root.querySelectorAll(css);
      return found.length === 1 && found[0] === target;
    } catch (e) {
      return false;
    }
  };

  // The CSS form of an own attribute (also the uniqueness check for testId / id / name).
  const cssFor = (name, v) => (name === 'id' ? '#' + CSS.escape(v) : attrCss(name, v));
  // The emitted locator for an own attribute; testId / id / name keep their kind unless cssOnly.
  const ownLocator = (name, v, cssOnly) => {
    if (!cssOnly && name === 'data-testid') return { kind: 'testId', value: v };
    if (!cssOnly && name === 'id') return { kind: 'id', value: v };
    if (!cssOnly && name === 'name') return { kind: 'name', value: v };
    return { kind: 'css', value: cssFor(name, v) };
  };

  // Tier 2: the node's own stable attributes, unique in its root.
  const ownTier = (node, root, cssOnly) => {
    for (const name of STABLE) {
      const v = stableValue(node, name);
      if (v && uniqueIn(root, cssFor(name, v), node)) return ownLocator(name, v, cssOnly);
    }
    return null;
  };

  // One path step: tag, an optional qualifier, and :nth-of-type only if same-looking siblings exist.
  const step = (node) => {
    let s = CSS.escape(node.localName);
    for (const name of QUALIFIERS) {
      const v = stableValue(node, name);
      if (v) { s += attrCss(name, v); break; }
    }
    const siblings = Array.from((node.parentElement || node.parentNode).children);
    if (siblings.filter((sib) => sib.matches(s)).length > 1) {
      const sameTag = siblings.filter((sib) => sib.localName === node.localName);
      s += ':nth-of-type(' + (sameTag.indexOf(node) + 1) + ')';
    }
    return s;
  };

  // Child-combinator path from just below `top` (exclusive; null = root) down to `node`.
  const pathBelow = (top, node) => {
    const steps = [];
    for (let n = node; n && n !== top; n = n.parentElement) steps.unshift(step(n));
    return steps.join(' > ');
  };

  // Tier 3: anchored on the nearest ancestor with a unique stable attribute.
  const anchoredTier = (node, root) => {
    for (let a = node.parentElement; a; a = a.parentElement) {
      for (const name of STABLE) {
        const v = stableValue(a, name);
        if (!v || !uniqueIn(root, cssFor(name, v), a)) continue;
        const css = cssFor(name, v) + ' > ' + pathBelow(a, node);
        if (uniqueIn(root, css, node)) return { kind: 'css', value: css };
        break; // this anchor's path is ambiguous; try the next ancestor up
      }
    }
    return null;
  };

  // Last resort: a structural path from the root. `:host` is not usable in
  // shadowRoot.querySelectorAll, so a shadow path starts at the top-level child.
  const rootPathTier = (node, root) => {
    let css = null;
    if (isDocument(root) && node !== root.documentElement) css = ':root > ' + pathBelow(root.documentElement, node);
    if (isShadowRoot(root)) css = pathBelow(null, node);
    return css && uniqueIn(root, css, node) ? { kind: 'css', value: css } : null;
  };

  // Tier 4: short visible text that no other element in `root` has as its own text.
  const textOf = (node) => (typeof node.innerText === 'string' ? node.innerText.replace(/\s+/g, ' ').trim() : '');
  const ownsText = (node, text) => textOf(node) === text && !Array.from(node.children).some((c) => textOf(c) === text);
  // Cheap pre-filter key: textContent needs no layout. Whitespace is dropped and case folded
  // because innerText differs from textContent in both (block breaks, text-transform).
  const textKey = (s) => s.replace(/\s+/g, '').toLowerCase();
  const textTier = (node, root) => {
    const text = textOf(node);
    if (!text || text.length > MAX_TEXT || !ownsText(node, text)) return null;
    const key = textKey(text);
    let owners = 0;
    for (const n of root.querySelectorAll('*')) {
      if (!textKey(n.textContent || '').includes(key)) continue; // innerText only for candidates
      if (ownsText(n, text) && ++owners > 1) return null;
    }
    return owners === 1 ? { kind: 'text', value: text } : null;
  };

  // CSS-only locator for a shadow host in its own root (attribute and structural tiers, no text).
  const hostCss = (host) => {
    const root = host.getRootNode();
    const found = ownTier(host, root, true) || anchoredTier(host, root) || rootPathTier(host, root);
    return found ? found.value : null;
  };

  // Main: the element's own locator, then shadow hops outward to the document.
  let root = el.getRootNode();
  if (!isDocument(root) && !isShadowRoot(root)) return null; // detached
  const skipText = !!opts.cssOnlyInShadow && isShadowRoot(root);
  const own = ownTier(el, root, false) || anchoredTier(el, root)
    || (skipText ? null : textTier(el, root)) || rootPathTier(el, root);
  if (!own) return null;

  const within = [];
  while (isShadowRoot(root)) {
    if (root.mode === 'closed') return null; // cannot be re-entered on replay
    const css = hostCss(root.host);
    if (!css) return null;
    within.unshift('shadow=' + css);
    root = root.host.getRootNode();
  }
  if (!isDocument(root)) return null;
  return { within: within, kind: own.kind, value: own.value };
}
