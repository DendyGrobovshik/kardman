# versions/

This directory holds the committed **native baseline** for every kernel module whose
`@RDMA` symbols are baked into the native host.

## Layout

```
versions/<moduleId>/rdma_hashes.json
```

- `<moduleId>` is the kernel module id (e.g. `user_alice`), the same value recorded in
  `rdma_analysis.json` under `moduleId`.
- `rdma_hashes.json` maps each declaration's fully-qualified name to its SHA-256 content hash:

  ```json
  {
    "moduleId": "user_alice",
    "hashes": {
      "com.example.kernel.user.alice.Person": "…",
      "com.example.kernel.user.alice.Person.greet": "…"
    }
  }
  ```

## Who reads / writes it

- **`static-release`** rewrites the baseline for a module after its symbols are baked into a new
  native build. It is the point at which the app "accepts" the current kernel implementation as
  native.
- **`dynamic-release`** reads the baseline and diffs it against the freshly emitted
  `rdma_analysis.json` to find the dirty subgraph (added / removed / changed declarations) that
  must be shipped as a polyfill.

## Notes

- The hash is computed over the **whole declaration** (signature + body), "as is" (no
  normalization). Reformatting or whitespace-only changes therefore count as a change; the
  `dynamic-release` report highlights the affected symbols to make this visible.
- Granularity is **member-level**: each class method/property is hashed separately, and the class
  header (annotations, modifiers, name, type parameters, supertypes, primary constructor) is
  hashed as its own structural unit.
- Do not hand-edit these files; regenerate them with `static-release`.
