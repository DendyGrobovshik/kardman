# Modifier composition benchmark

Guest grid on a Pixel 8 emulator (API 15), package `org.example`. One instrumentation pass per number. The timed region is the guest `ModifiersBench` body (32 rows × 4 columns). Both passes keep row and column layout modifiers. The Modifiers pass adds an 8-element chain on every cell (`weight`, `paddingAll`, `background`, `paddingSymmetric`, `alpha`, `offset`, `alignVertically`, `testTag`). The NoModifiers pass is `Text(label)` with no cell chain.

```
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}" GRADLE_USER_HOME="$HOME/.gradle" ./gradlew -p demo :androidApp:guestComposeBench --console=plain
```

`modifiers host composition` is the UI-thread time of the first `RdmaComposeHost.Content()` call only. It covers the Modifiers pass, not NoModifiers.

## Eager RDMA chain

Each `Modifier()` and each chain method is a host call while the guest composes. A cell chain is nine host calls (create plus eight methods), plus the `Dp` / `Color` / `Shape` / `Alignment` handles those methods store.

| | composition | recomposition |
|---|---:|---:|
| Modifiers | 282.0 ms | 213.0 ms |
| NoModifiers | 233.0 ms | 134.0 ms |

Modifiers host composition: 297.9 ms.

The host JVM benchmark (`ModifierCompositionBenchTest`) does not run guest code. Medians there, warmup 2 and 5 runs: Modifiers composition 1.4 ms, recomposition 3.1 ms; NoModifiers composition 0.4 ms, recomposition 0.1 ms.

## Live chain, RDMA on request

The guest keeps the chain as `LiveModifier` ops. A widget calls `request()`, which is one `RDMA.materializeModifier` host call. That call creates the kernel `Modifier` and applies every stored element before returning the handle. `Dp`, `Color`, `Shape`, and `Alignment` arguments are still created when the guest records the op.

| | composition | recomposition | vs eager composition | vs eager recomposition |
|---|---:|---:|---:|---:|
| Modifiers | 261.0 ms | 213.0 ms | −21.0 ms | 0.0 ms |
| NoModifiers | 218.0 ms | 132.0 ms | −15.0 ms | −2.0 ms |

Modifiers host composition: 281.8 ms (eager 297.9 ms, −16.1 ms).

Same command, same Pixel 8 emulator, one pass. The modifier-minus-plain gap is 43.0 ms composition and 81.0 ms recomposition (eager gap was 49.0 ms and 79.0 ms). Recomposition of the modifier grid is the same 213.0 ms. Composition of both grids moved by a similar amount, which sits inside the single-pass spread seen on this emulator. Collapsing eight modifier method crossings into one `materializeModifier` call did not remove the modifier cost. Each element was still applied with its own JNI method inside that host entry, and `Dp`, `Color`, `Shape`, and `Alignment` are still created while the guest records the chain.

## One JNI call for the whole chain

`materializeModifier` boxes every recorded element into three arrays and calls `applyModifierChain` once. The host walks those arrays and builds the chain in Kotlin. There is no `CallObjectMethod` per element. `Dp`, `Color`, `Shape`, and `Alignment` are still created while the guest records the chain.

| | composition | recomposition | vs per-element JNI composition | vs per-element JNI recomposition |
|---|---:|---:|---:|---:|
| Modifiers | 266.0 ms | 207.0 ms | +5.0 ms | −6.0 ms |
| NoModifiers | 231.0 ms | 179.0 ms | +13.0 ms | +47.0 ms |

Modifiers host composition: 291.1 ms.

Same emulator, one pass. Modifier composition stayed next to the per-element JNI run (266.0 vs 261.0). The modifier-minus-plain gap is 35.0 ms composition and 28.0 ms recomposition. The smaller recomposition gap comes with a slower plain grid (179.0 vs 132.0), so this pass does not show the single JNI call beating a JNI call per element.
