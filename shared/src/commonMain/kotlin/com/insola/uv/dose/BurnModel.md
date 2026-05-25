# BurnModel — accuracy notes

Cross-check of [`BurnModel.kt`](BurnModel.kt) and the MED constants in
[`Models.kt`](../domain/Models.kt) against the standard erythemal-dose
literature (WHO/CIE, TEMIS, McKinlay–Diffey).

## Conventions and unit math

- UV index is defined as erythemally weighted irradiance / 25 mW·m⁻²
  (Canadian convention, adopted by WHO/CIE).
- 1 UVI = 25 mW/m² ⇒ **1 UVI·h = 0.025 × 3600 = 90 J/m²** erythemal.
- Per-type MED thresholds (`SkinSensitivity.medThresholdUvIndexHours`) are
  the published J/m² MEDs divided by 90.
- Because UV index already carries the McKinlay–Diffey action spectrum,
  `∫ UVI dt · factor` is directly proportional to erythemal dose in MEDs;
  no separate spectral weighting is needed.

## Numerics

- Segment dose uses the trapezoidal rule on a linear UV ramp:
  `0.5·(uvA+uvB)·Δt·factor` — this is the exact integral, not an
  approximation. TEMIS performs the analogous integration on a fixed
  5-minute step.
- Within the crossing segment, dose is
  `factor·(uvA·t + 0.5·slope·t²)` with `slope = (uvB-uvA)/hours`.
  `solveCrossingHours` solves the resulting quadratic and picks the
  smallest non-negative root in `[0, hours]`. Correct for both positive
  and negative slope.
- Sanity check at constant UVI: Type II at UVI 6, factor = 1 gives
  `t = 2.8 / 6 ≈ 28 min`, matching the figure widely quoted in burn-time
  tables.

## Known limitations

- **Phototype is a poor individual predictor of MED.** Clinical MED
  scatter within a single Fitzpatrick type is large (NIH PMC3734971).
  Any phototype-driven model inherits this; it is not specific to this
  implementation.
- **MED-per-type values vary across sources.** Insola uses
  200 / 250 / 350 / 450 / 600 / 1000 J/m² (I–VI). A second commonly
  cited set is 200 / 250 / 300 / 400 / 500 / 600 J/m² (a flatter curve).
  No single canonical standard exists; Insola sits on the more
  conservative (longer-tolerance) end for types III–VI.
- **`assumedFactor` collapses several effects** (effective SPF, clothing,
  surface reflection, altitude, residual cloud). Callers must avoid
  double-counting — e.g. open-meteo UV index is already
  cloud-attenuated, so cloud cover should not be re-applied via factor.
- **`disc < 0` fallback in `solveCrossingHours`** returns `hours`. The
  function is only called when the caller has established that the
  segment crosses the threshold, so this fires only on floating-point
  edge cases and is intentionally conservative.

## Sources

- [TEMIS — UV index and UV dose: details of the calculation](https://www.temis.nl/uvradiation/product/uvi-uvd.html)
- [TEMIS — UV index and UV dose: short introduction](https://www.temis.nl/uvradiation/info/index.html)
- [Wikipedia — Ultraviolet index](https://en.wikipedia.org/wiki/Ultraviolet_index)
- [Bentham Instruments — Erythemal radiant exposure and UV index](https://support.bentham.co.uk/support/solutions/articles/5000619299-erythemal-radiant-exposure-and-uv-index)
- [NIH PMC3734971 — Minimal Erythema Dose (MED) Testing](https://pmc.ncbi.nlm.nih.gov/articles/PMC3734971/)
- [Public Health Messages Associated with Low UV Index Values Need Reconsideration (PMC6617134)](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC6617134/)
- [Actas Dermo-Sifiliográficas — MED correlation with Fitzpatrick type](https://actasdermo.org/en-minimal-erythema-dose-correlation-with-articulo-S1578219020301505)
- [Nature Scientific Reports — UVI vs first/second/third-degree sunburn (Probit)](https://www.nature.com/articles/s41598-018-36850-x)
- [ScanSkinAI — UV index burn time chart](https://www.scanskinai.com/blog/uv-index-burn-time)
- [healthcalculatoronline — UV index calculator](https://healthcalculatoronline.com/uv-index-calculator/)
