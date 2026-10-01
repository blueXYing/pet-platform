type FileSystem = {
  readdirSync(path: string): string[]
  unlinkSync(path: string): void
  writeFileSync(path: string, data: ArrayBuffer): void
}
const NAME = /^pet-aftersale-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.img(?![\s\S])/i
/** Owns only downloaded previews, never durable upload-journal files. */
export class PrivateEvidenceFiles {
  private paths = new Set<string>()
  constructor(private fs: FileSystem, private root: string, recover = false) {
    if (recover) for (const name of fs.readdirSync(root)) if (NAME.test(name)) this.paths.add(`${root}/${name}`)
  }
  save(uuid: string, data: ArrayBuffer) {
    const name = `pet-aftersale-${uuid}.img`
    if (!NAME.test(name)) throw new Error('INVALID_PREVIEW_NAME')
    const path = `${this.root}/${name}`
    // Record ownership before writing, so even partially written files can be cleared.
    this.paths.add(path); this.fs.writeFileSync(path, data)
    return path
  }
  clear() {
    for (const path of this.paths) {
      try { this.fs.unlinkSync(path); this.paths.delete(path) }
      catch { /* retain ownership for the next hide/scope/startup cleanup */ }
    }
  }
}
