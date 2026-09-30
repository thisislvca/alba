const iconPaths = {
  phone: '<path fill-rule="evenodd" d="M18 4h28a7 7 0 0 1 7 7v42a7 7 0 0 1-7 7H18a7 7 0 0 1-7-7V11a7 7 0 0 1 7-7Z M18 14v32h28V14Z M27 51v4h10v-4Z"/>',
  photos: '<path fill-rule="evenodd" d="M8 4h30a6 6 0 0 1 6 6v6H18a8 8 0 0 0-8 8v22H8a6 6 0 0 1-6-6V10a6 6 0 0 1 6-6Z M22 20h34a6 6 0 0 1 6 6v30a6 6 0 0 1-6 6H22a6 6 0 0 1-6-6V26a6 6 0 0 1 6-6Z M24 52h30L43 37l-8 9-5-6Z M29 28a5 5 0 1 0 0 10 5 5 0 0 0 0-10Z"/>',
  devices: '<path fill-rule="evenodd" d="M8 5h21a5 5 0 0 1 5 5v43a5 5 0 0 1-5 5H8a5 5 0 0 1-5-5V10a5 5 0 0 1 5-5Z M9 14v32h19V14Z M14 50v3h10v-3Z M41 12h15a5 5 0 0 1 5 5v38a5 5 0 0 1-5 5H41a5 5 0 0 1-5-5V17a5 5 0 0 1 5-5Z M42 20v28h13V20Z M46 52v3h5v-3Z"/>',
  backup: '<path fill-rule="evenodd" d="M18 49h29a13 13 0 0 0 1-26 18 18 0 0 0-34-3A15 15 0 0 0 18 49Z M28 46V35h-7l11-11 11 11h-7v11Z"/>',
  zoom: '<path fill-rule="evenodd" d="M27 6a21 21 0 1 0 0 42 21 21 0 0 0 0-42Z M27 14a13 13 0 1 1 0 26 13 13 0 0 1 0-26Z"/><path d="m39 44 6-6 16 16-6 6Z"/>',
  cloud: '<path d="M18 49h29a13 13 0 0 0 1-26 18 18 0 0 0-34-3A15 15 0 0 0 18 49Z"/>',
  offline: '<path d="M28 8h8v24h10L32 46 18 32h10Z M8 44h8v8h32v-8h8v10a6 6 0 0 1-6 6H14a6 6 0 0 1-6-6Z"/>',
  video: '<path fill-rule="evenodd" d="M17 12h30a11 11 0 0 1 11 11v18a11 11 0 0 1-11 11H17A11 11 0 0 1 6 41V23a11 11 0 0 1 11-11Z M27 22v20l17-10Z"/>',
  family: '<circle cx="23" cy="20" r="10"/><circle cx="47" cy="24" r="8"/><path d="M4 54v-5a19 19 0 0 1 38 0v5Z M44 54v-5a23 23 0 0 0-3-12 15 15 0 0 1 20 14v3Z"/>',
  share: '<path d="m17 28 27-16 4 7-27 16Z m4 1 27 16-4 7-27-16Z"/><circle cx="47" cy="13" r="10"/><circle cx="15" cy="32" r="10"/><circle cx="47" cy="51" r="10"/>',
};

function icon(name, x, y, size, color, androidImage) {
  if (name === 'android') {
    const tinted = `data:image/svg+xml;base64,${Buffer.from(androidImage.replace(/#34a853/gi, color)).toString('base64')}`;
    return `<image x="${x}" y="${y}" width="${size}" height="${size}" href="${tinted}"/>`;
  }
  if (!iconPaths[name]) throw new Error(`Unknown callout icon: ${name}`);
  return `<svg x="${x}" y="${y}" width="${size}" height="${size}" viewBox="0 0 64 64" color="${color}" fill="currentColor">${iconPaths[name]}</svg>`;
}

function segments(line, callouts) {
  const matches = callouts.map(callout => {
    const start = line.indexOf(callout.text);
    if (start < 0 || line.indexOf(callout.text, start + 1) >= 0) throw new Error(`Callout must match one phrase: ${callout.text}`);
    return { ...callout, start, end: start + callout.text.length };
  }).sort((a, b) => a.start - b.start);
  const result = [];
  let cursor = 0;
  for (const match of matches) {
    if (match.start < cursor) throw new Error('Callouts must not overlap');
    if (match.start > cursor) result.push({ text: line.slice(cursor, match.start) });
    result.push(match);
    cursor = match.end;
  }
  if (cursor < line.length) result.push({ text: line.slice(cursor) });
  return result;
}

export function renderTitles(font, card, config, androidImage) {
  const { title, phone } = config.style;
  const lines = card.lines.map((line, row) => {
    const runs = segments(line, (card.callouts || []).filter(c => c.line === row));
    const layout = size => {
      let cursor = 0;
      const options = { letterSpacing: title.letterSpacing / size };
      const parts = runs.map(run => {
        const path = font.getPath(run.text, 0, 0, size, options);
        const textWidth = font.getAdvanceWidth(run.text, size, options);
        let content, width = textWidth;
        if (run.icon) {
          const { height, top: pillTop, iconSize, paddingLeft, paddingRight, gap } = title.pill;
          width += paddingLeft + paddingRight + iconSize + gap;
          const tone = title.pills[run.tone || 'accent'];
          if (!tone) throw new Error(`Unknown callout tone: ${run.tone}`);
          path.fill = tone.text;
          content = `<rect y="${pillTop}" width="${width}" height="${height}" rx="${height / 2}" fill="${tone.fill}"/>`
            + icon(run.icon, paddingLeft, pillTop + (height - iconSize) / 2, iconSize, tone.icon, androidImage)
            + `<g transform="translate(${paddingLeft + iconSize + gap},0)">${path.toSVG(3)}</g>`;
        } else {
          path.fill = title.color;
          content = path.toSVG(3);
        }
        const result = `<g transform="translate(${cursor},0)">${content}</g>`;
        cursor += width;
        return result;
      });
      return { width: cursor, svg: parts.join('') };
    };
    // One type size across every line and pill; shorten copy rather than shrink it.
    const measured = layout(title.size);
    if (measured.width > title.maxWidth) throw new Error(`Headline is too wide: ${line}`);
    return { ...measured, baseline: row * title.lineHeight };
  });
  const top = title.pill.top;
  const bottom = (lines.length - 1) * title.lineHeight + title.pill.top + title.pill.height;
  const offset = (phone.top - 6) / 2 - (top + bottom) / 2;
  // Use common line boxes so every card has identical, centered title baselines.
  return `<g transform="translate(0,${offset})">${lines.map(l => `<g transform="translate(${(config.width - l.width) / 2},${l.baseline})">${l.svg}</g>`).join('')}</g>`;
}

export function renderAttribution(font, card, width, height) {
  if (!card.attribution) return '';
  const size = card.attribution.length <= 2 ? 20 : 16;
  const lineHeight = size + 6;
  const footerHeight = card.attribution.length * lineHeight + 20;
  return `<rect y="${height - footerHeight}" width="${width}" height="${footerHeight}" fill="#FFF1E9"/>`
    + card.attribution.map((text, index) => {
      const path = font.getPath(text, 0, height - footerHeight + size + 8 + index * lineHeight, size);
      const bounds = path.getBoundingBox();
      if (bounds.x2 - bounds.x1 > width - 60) throw new Error('Attribution is too wide');
      path.fill = '#665568';
      return `<g transform="translate(${width / 2 - (bounds.x1 + bounds.x2) / 2},0)">${path.toSVG(3)}</g>`;
    }).join('');
}

export function renderNote(font, card, config) {
  return (card.note || []).map((text, index) => {
    const path = font.getPath(text, 0, config.style.phone.top - 56 + index * 38, 32);
    const bounds = path.getBoundingBox();
    if (bounds.x2 - bounds.x1 > config.width - 100) throw new Error('Screenshot note is too wide');
    path.fill = '#453D49';
    return `<g transform="translate(${config.width / 2 - (bounds.x1 + bounds.x2) / 2},0)">${path.toSVG(3)}</g>`;
  }).join('');
}
