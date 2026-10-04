(el, opts) => {
  // Derives a uniqueness-checked locator for `el`: {within: string[], kind, value} or null.
  // Framework-neutral (Playwright evaluate / Selenium executeScript). `within` lists the
  // `shadow=<css>` hops of the open shadow roots `el` sits in, outermost first.
  // Tier order: own stable attribute, path from a stable ancestor anchor, unique short text,
  // structural path from the root (:root / shadow top-level child), else null.
  // opts.cssOnlyInShadow: skip the text tier inside shadow roots (Selenium's text locator is XPath).
  // opts.pierce: count uniqueness matches in the root plus every OPEN shadow root nested below it,
  //   for engines whose replay pierces shadow DOM (Playwright: true; Selenium: false).
  // opts.cssOnly: the element's own locator is always kind 'css' (testId / id / name become
  //   attribute or #id CSS, escaped here with CSS.escape; no text tier). Used for an iframe
  //   element, whose frame= hop must be CSS.
  opts = opts || {};
  const pierce = !!opts.pierce;
  const cssOnly = !!opts.cssOnly;

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

  // The roots a uniqueness check searches: `root`, plus (with pierce) its nested open shadow roots.
  const scopeCache = new Map();
  const scopesOf = (root) => {
    if (!pierce) return [root];
    if (scopeCache.has(root)) return scopeCache.get(root);
    const scopes = [];
    const collect = (r) => {
      scopes.push(r);
      for (const n of r.querySelectorAll('*')) if (n.shadowRoot) collect(n.shadowRoot); // open roots only
    };
    collect(root);
    scopeCache.set(root, scopes);
    return scopes;
  };

  // True when `css` matches exactly one node in the scopes of `root`, and that node is `target`.
  const uniqueIn = (root, css, target) => {
    try {
      let found = null;
      for (const scope of scopesOf(root)) {
        for (const n of scope.querySelectorAll(css)) {
          if (found) return false;
          found = n;
        }
      }
      return found === target;
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

  // Tier 4: short DOM text (what getByText / XPath text() match, not rendered innerText).
  // The value is the element's own text nodes, whitespace-collapsed; exactly one of them may be
  // non-blank (XPath text() reads only the first), and no descendant element may add text.
  const ownText = (node) => {
    const parts = [];
    for (const c of node.childNodes) if (c.nodeType === 3 && c.data.trim()) parts.push(c.data);
    return parts.length === 1 ? parts[0].replace(/\s+/g, ' ').trim() : '';
  };
  const noChildText = (node) => Array.from(node.children).every((c) => !(c.textContent || '').trim());
  // Own text is checked first: it reads only direct children, so the subtree scan runs for candidates only.
  const ownsText = (node, text) => ownText(node) === text && noChildText(node);
  const textTier = (node, root) => {
    const text = ownText(node);
    if (!text || text.length > MAX_TEXT || !noChildText(node)) return null;
    // Visibility only: innerText is empty for visibility:hidden content.
    if (typeof node.innerText !== 'string' || !node.innerText.trim()) return null;
    let owners = 0;
    for (const scope of scopesOf(root)) {
      for (const n of scope.querySelectorAll('*')) if (ownsText(n, text) && ++owners > 1) return null;
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
  const skipText = cssOnly || (!!opts.cssOnlyInShadow && isShadowRoot(root));
  const own = ownTier(el, root, cssOnly) || anchoredTier(el, root)
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
