/** 有限并发任务队列（默认用于 Tokenlab 分图渲染）。 */
export async function runPool<T>(
  items: T[],
  concurrency: number,
  worker: (item: T, index: number) => Promise<void>,
): Promise<void> {
  if (!items.length) return
  const limit = Math.max(1, Math.min(concurrency, items.length))
  let next = 0
  const runners = Array.from({ length: limit }, async () => {
    while (true) {
      const i = next
      next += 1
      if (i >= items.length) break
      await worker(items[i], i)
    }
  })
  await Promise.all(runners)
}
