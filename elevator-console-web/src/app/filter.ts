// Name filter shared by both tabs (same behaviour as the Rust console): try the query
// as a case-insensitive regex, fall back to a plain substring match if it doesn't compile.
// An empty/whitespace query matches everything.

export function nameFilter(query: string): (name: string) => boolean {
  const q = query.trim();
  if (!q) {
    return () => true;
  }
  try {
    const re = new RegExp(q, 'i');
    return (name) => re.test(name);
  } catch {
    const lower = q.toLowerCase();
    return (name) => name.toLowerCase().includes(lower);
  }
}
