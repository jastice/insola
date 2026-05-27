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

## Skin model: phototype × acclimatization

[`SkinProfile`](../domain/Models.kt) combines an immutable Fitzpatrick
[`SkinSensitivity`](../domain/Models.kt) (baseline untanned MED) with a
mutable [`Acclimatization`](../domain/Models.kt) state. The effective MED
used everywhere by the burn integrator is

```
effective_MED = baseline_MED(phototype) × min(acclimatization.factor,
                                              phototype.cap)
```

### Acclimatization factors

Repeated sub-erythemal UV drives two roughly independent photoadaptation
mechanisms:

  - **Facultative melanogenesis** — eumelanin production peaks ~7 days
    after exposure and decays over ~4–6 weeks. Provides up to ~2× MED.
  - **Stratum-corneum thickening (hyperkeratosis)** — UV-B-driven
    epidermal hyperplasia adds a further ~1.5× on top, slower onset
    (~10–14 days), similar decay timescale.

The four picker levels are calibrated to the combined effect at
plausible exposure histories:

| Level    | Factor | Roughly corresponds to                          |
| -------- | -----: | ----------------------------------------------- |
| None     |    1.0 | Winter skin / no UV in the past 4+ weeks        |
| Light    |    1.5 | Occasional outdoor time the past 2 weeks        |
| Moderate |    2.2 | Daily outdoor exposure, established tan         |
| Deep     |    3.0 | Frequent unprotected sun                        |

### Per-phototype caps

Tanning capacity is itself phototype-dependent — type I has minimal
melanogenic response while V–VI can roughly quadruple their baseline
tolerance (Sheehan 2002; Miyamura 2011). `maxAcclimatizationFactor`
clips the chosen level so a "Deep tan" pick on a type-I user does not
push the model past their physiological ceiling:

| Phototype | Cap   |
| --------- | ----: |
| I         |  1.4× |
| II        |  1.8× |
| III       |  2.5× |
| IV        |  3.0× |
| V         |  3.5× |
| VI        |  4.0× |

### Vitamin D vs acclimatization

The SDD threshold itself is anchored to **baseline** phototype MED —
Holick's "¼ MED on ¼ body" calibration uses untanned skin, so the
bucket denominator (`phototype.medThresholdUvIndexHours / 16`) does
not move with tan. Phototype-level constitutive melanin is already
encoded in that baseline MED.

What tan *does* change is the **yield** per unit raw UV. Melanin and
7-dehydrocholesterol compete for the same UV-B photons (Webb 2010,
Bogh 2010), so an acclimatized epidermis converts fewer of them into
previtamin-D3. The model represents this as a divide-by-factor on the
raw integrator output before bucket comparison:

```
effective_yield = raw_score / min(acclimatization.factor,
                                  phototype.cap)
```

implemented in [`VitaminDModel.effectiveYield`](VitaminDModel.kt) and
applied in `VitaminDModel.bucket` and the dashboard's vitamin-D bar.

This composes symmetrically with the burn-side multiplier: tan extends
the burn budget **and** lengthens the time to 1 SDD by the same
factor. The "Skin" tab visualizes both rows on a single time axis so
the trade-off is visible at a glance.

### What this model still does *not* capture

- **Time history.** Acclimatization is self-reported, not derived from
  logged exposure. A "Moderate tan" pick after one beach weekend is
  treated identically to a Moderate tan after six weeks of gardening.
- **Decay.** No automatic ramp-down — users must move the picker back
  when their tan fades.
- **Body-site variation.** Face MED is roughly 1.5× lower than back MED
  (Lock-Andersen 1996); the model treats the body as homogeneous.
- **Spectrum-specific adaptation.** Melanin protects more in the UV-A
  range; stratum-corneum protects more in UV-B. The cumulative ×N
  multiplier on the erythemal-weighted MED is a single-number summary.

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

### Photoadaptation / acclimatization

- [Sheehan & Young 2002 — The sunburn cell revisited: an update on mechanistic aspects (Photochem Photobiol Sci)](https://pubmed.ncbi.nlm.nih.gov/12659556/) — review of MED elevation with regular sub-erythemal exposure; ~2–3× typical, up to ~4× in darker phototypes.
- [Miyamura et al. 2011 — The deceptive nature of UVA tanning vs the modest protective effects of UVB tanning (Pigment Cell Melanoma Res)](https://pubmed.ncbi.nlm.nih.gov/21232026/) — quantifies tan-induced photoprotection; melanin contributes ~SPF 3 at most for natural tans.
- [Diffey 1991 — Solar ultraviolet radiation effects on biological systems (Phys Med Biol)](https://iopscience.iop.org/article/10.1088/0031-9155/36/3/001) — phototype-dependent tanning capacity and MED scatter.
- [Lock-Andersen et al. 1996 — Threshold level for measurement of UV sensitivity (Br J Dermatol)](https://pubmed.ncbi.nlm.nih.gov/8731867/) — body-site MED variation and individual scatter.
- [Olsen & Diffey 2002 — A practical method for determining personal UV exposure (J Photochem Photobiol B)](https://pubmed.ncbi.nlm.nih.gov/12100854/) — methodology for relating exposure history to acclimatization state.

### Vitamin D vs melanin

- [Webb & Engelsen 2006 — Calculated ultraviolet exposure levels for a healthy vitamin D status (Photochem Photobiol)](https://pubmed.ncbi.nlm.nih.gov/17017847/) — FastRT vitamin-D-vs-erythemal weighting tables underlying [`VitaminDModel.vitDRatio`](VitaminDModel.kt).
- [McKenzie, Liley & Björn 2009 — UV radiation: balancing risks and benefits (Photochem Photobiol)](https://pubmed.ncbi.nlm.nih.gov/19320857/) — quantitative relationship between solar elevation, UVI and previtamin-D3 synthesis.
- [Webb et al. 2010 — Influence of season and latitude on the cutaneous synthesis of vitamin D3 (J Endocrinol)](https://pubmed.ncbi.nlm.nih.gov/19625280/) — pigmented skin produces less previtamin-D3 per unit UV; basis for the `effectiveYield` attenuation.
- [Bogh et al. 2010 — Vitamin D production after UVB exposure depends on baseline vitamin D and total cholesterol but not on skin pigmentation (J Invest Dermatol)](https://pubmed.ncbi.nlm.nih.gov/20445559/) — nuance on the constitutive vs acclimatized melanin contribution, motivating the decision to attenuate by acclimatization factor only (phototype baseline already in the SDD denominator).
- [Holick 2007 — Vitamin D deficiency (NEJM)](https://pubmed.ncbi.nlm.nih.gov/17634462/) — "¼ MED on ¼ body ≈ 1000 IU" calibration anchoring the SDD definition.

### Erythemal-dose calculation

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
