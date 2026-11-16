/** Invalidates results from an earlier account or navigation context. */
export function createTaskGuard() {
  let revision = 0;
  return {
    invalidate() {
      revision++;
    },
    capture() {
      const current = revision;
      return () => current === revision;
    },
  };
}
