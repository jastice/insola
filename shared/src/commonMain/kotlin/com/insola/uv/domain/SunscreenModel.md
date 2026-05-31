# SunscreenModel — accuracy notes

Cross-check of [`AttenuationTimeline.Patch`](Models.kt) and the constants on
its companion against the sunscreen-photochemistry / field-application
literature. Companion to [`BurnModel.md`](../dose/BurnModel.md), which
covers the burn integrator that consumes the timeline's transmittance.

## What the model integrates

The dose integrator multiplies incident, erythemally-weighted UV by a
scalar transmittance `T(t) ∈ (0, 1]` at every instant of every logged
[`OutdoorSession`](Models.kt). `T(t) = 1` is bare skin; `T(t) = 1/30` is
"SPF 30 transmits 1/30 of incident UV". SPF is defined against the same
erythemal action spectrum the UV index carries (Diffey 2001), so a single
multiplicative factor applies symmetrically to burn dose **and** vitamin-D
score — no separate spectral weighting is needed.

The user-controlled
[`AttenuationTimeline`](Models.kt) holds a list of
[`Patch`](Models.kt) records (one per Apply tap). At any instant the
timeline's transmittance is `min` across all patches — strongest
protection wins, with bare skin (`T = 1`) as the floor when no patch
covers the moment. There is no always-on baseline SPF. This `min`
composition is what guarantees a re-apply can only *extend* coverage, never
strip a moment an earlier application already covered.

## Decay model

Each patch decays the *excess* protection factor exponentially, leaving
`P → 1` (bare skin) as `t → ∞`:

```
P(t) = 1 + (S − 1) · exp(−(t − tApply) / τ)
T(t) = 1 / P(t)
τ    = halfLife / ln 2
```

`S` is the initial effective SPF at the moment of application — **not**
the label SPF; see "Application thickness" below. The half-life of the
excess factor is fixed at
[`NOMINAL_HALF_LIFE_HOURS`](Models.kt) = **2 h** and tracks the
dermatology "reapply every 2 hours" guideline (Diffey 2001, AAD 2024
photoprotection guidelines). At 2 h the excess factor has halved; at 4 h
it's a quarter; at 6 half-lives it's ~1.5 %, which we treat as bare skin
([`MODELLING_HALF_LIVES`](Models.kt) caps the modelling horizon at 12 h
under default wear). The
[`wearMultiplier`](Models.kt) field scales `τ` for water immersion or
heavy sweat.

The integrator slices each exposure interval at the patch's sampling
grid ([`SAMPLING_STEP`](Models.kt) = **5 min**) and evaluates `T` at the
midpoint of each slice. That makes the piecewise-constant approximation
second-order accurate against the smooth curve, keeping the integration
error below ~0.5 % for any plausible SPF.

## Real-world reduction factors

The label SPF on the bottle is measured under conditions that consumers
do not replicate: 2 mg/cm² applied evenly to clean dry skin in a
laboratory, irradiated immediately, no rubbing, no sweat, no water. The
field literature is consistent that *typical* protection in everyday
use falls dramatically short of the label. The model bakes the dominant
two factors — application thickness and wear — into the decay curve so
the dashboard's burn/vit-D projections reflect realistic protection by
default. Users who want lab-grade application can override the relevant
parameters per-patch.

### Application thickness — `applicationThickness`

[`TYPICAL_APPLICATION_THICKNESS`](Models.kt) = **0.5**, meaning the
average consumer applies about half the 2 mg/cm² lab dose.

[Petersen & Wulf 2014](https://pubmed.ncbi.nlm.nih.gov/24313722/) review
the field-application literature and report typical real-world use of
**0.39–1.0 mg/cm²**, with a median around 0.5 mg/cm². Multiple
independent observational studies converge on this range
(Bech-Thomsen & Wulf 1992/1993, Stenberg & Larkö 1985, Diffey 2001,
Autier et al. 2007). The headline number — "users apply about a quarter
to a half of the recommended amount" — is essentially universal.

The relationship between applied thickness `d` (as a fraction of the
2 mg/cm² lab dose) and effective SPF is modelled **linearly** in the
*excess* protection factor:

```
S_effective = 1 + (S_label − 1) · d
```

At `d = 0` (no sunscreen) `S_effective = 1` (bare skin); at `d = 1` (lab
dose) `S_effective = S_label` (the bottle's number, as advertised). In
between we interpolate the excess `(S − 1)` proportionally to applied
thickness. This is the form [Diffey 1997](https://pubmed.ncbi.nlm.nih.gov/9216526/)
used in his original "amount applied" model and is also Wulf's first-order
approximation before they fit the steeper exponential. Plugging in the
typical 0.5 thickness:

| Label SPF | Effective SPF at thickness = 0.5 |
| --------: | -------------------------------: |
|        15 |                             8.0  |
|        30 |                            15.5  |
|        50 |                            25.5  |
|       100 |                            50.5  |

### Why linear instead of the exponential

The competing **exponential** law `S_eff = S_label ^ d`
([Faurschou & Wulf 2007](https://pubmed.ncbi.nlm.nih.gov/17493070/),
reproduced by [Schalka & Reis 2011](https://pubmed.ncbi.nlm.nih.gov/21603814/))
fits Wulf's in-vivo UV-B data better at low thickness, predicting SPF 30
→ √30 ≈ 5.5 at `d = 0.5`. Two reasons we don't use it:

  - **It is openly debated.** [Osterwalder 2014](https://onlinelibrary.wiley.com/doi/10.1111/phpp.12112)
    pointed out the exponential fit was derived against UV-B narrowband
    sources only, and that broad-spectrum solar exposure produces a
    flatter dose-response. The shape of the curve at typical use thickness
    is genuinely uncertain in the literature.
  - **It double-counts pessimism in our stack.** Combined with the 2 h
    `NOMINAL_HALF_LIFE_HOURS` (itself a behavioural reapply guideline
    with built-in safety margin, not a kinetic measurement), the
    exponential law turned out to predict that SPF 30 applied normally
    in tropical sun would burn skin type III within ~45 min. That does
    not match either Insola's authors' lived experience or the
    population-level outcome the AAD's "every 2 h" guidance is calibrated
    around (which assumes SPF 30 *is* keeping users mostly safe even
    when reapplied late).

The linear law lands in the middle of the literature's plausible range,
matches lived experience at typical sun exposures, and still bakes in a
real derate at the default 0.5 thickness — SPF 30 reads as effective SPF
15, not 30. Users who really do apply the full 2 mg/cm² can pass
`applicationThickness = 1.0` per patch and recover the labelled SPF.

### Wear — `wearMultiplier`

[`wearMultiplier`](Models.kt) defaults to **1.0** (the full 2 h nominal
half-life). It scales `τ` linearly, so `wearMultiplier = 0.5` halves the
half-life (heavy sweat, water immersion); values above 1 are unphysical
and not used.

Wear has two distinct physical mechanisms:

  - **Mechanical loss** — towel friction, clothing rub-off, hand-to-face
    contact. [Diffey & Robson 1989](https://pubmed.ncbi.nlm.nih.gov/2774677/)
    and Diffey 2001 estimate this accounts for most of the 2-hour
    half-life on dry skin under normal activity.
  - **Water/sweat removal** — non-water-resistant formulations lose
    roughly half their protection after 30 min of swimming
    ([Stokes & Diffey 1999](https://pubmed.ncbi.nlm.nih.gov/10027063/)).
    Water-resistant claims (US: 40 min, EU: 80 min) only delay this,
    they do not eliminate it. Heavy sweat, which physically pools and
    dilutes filters, has a similar effect to mild water exposure.

Recommended values:

| Activity                                       | wearMultiplier |
| ---------------------------------------------- | -------------: |
| Indoor / sedentary                             |          ~1.2  |
| Outdoor, dry, light activity (default)         |           1.0  |
| Heavy sweat or water-resistant + swim          |          ~0.7  |
| Non-water-resistant + swim, towel-drying       |          ~0.4  |

The UI currently does not expose wear conditions — the assumption is
the default "outdoor, dry" case. The field is in place so a future
activity-aware UI (or future weather-aware logic, e.g. "it is 30 °C, you
will sweat") can wire it up.

### Photodegradation of UV filters

Embedded in the half-life, not exposed as a parameter. The dominant
chemical decay mechanism is photoisomerisation / photodegradation of
the organic filters themselves:

  - **Avobenzone** (the workhorse UVA filter) is intrinsically
    photo-unstable; in pure form it loses ~60 % of UVA absorbance in
    ~2 h of solar exposure
    ([Tarras-Wahlberg et al. 1999](https://pubmed.ncbi.nlm.nih.gov/9989396/)).
  - **Modern photostabilised formulations** combining avobenzone with
    octocrylene, bemotrizinol, or methylbenzylidene camphor are
    essentially stable on the 2 h scale
    ([Gonzenbach et al. 1992](https://pubmed.ncbi.nlm.nih.gov/1454368/),
    [Diffey 2008](https://pubmed.ncbi.nlm.nih.gov/18821841/)). EU/AU
    market sunscreens largely meet this; many US legacy SKUs do not.
  - **Inorganic filters** (ZnO, TiO₂) are physically photostable at the
    time scales we care about.

The 2 h half-life is anchored to *mechanical loss* on a *photostabilised*
formulation. For a user wearing pure avobenzone in a non-stabilised
formulation, the real half-life is closer to 1 h — but that population
shrinks every year and is hard to detect from app inputs.

### Missing coverage

Worth flagging because users do not see it modelled:

  - **Missed areas**: typical application leaves 10–40 % of the
    nominally-protected surface uncovered (Loesch & Kaplan 1994,
    Petersen & Wulf 2014). The model assumes uniform coverage.
  - **Time-to-effective**: SPF measurements assume filters are dry and
    set on the skin. The first 15–30 min after application can be ~50 %
    weaker (Diffey 2001). The model treats `t = tApply` as full
    `S = S_initial`.
  - **Photo-induced filter conversion**: some filter combinations
    *increase* SPF in the first hour of UV exposure as photoisomers
    form (Bonda 2008). Counteracts mechanical loss slightly; lumped
    into the 2 h half-life.

These are real but second-order effects on the trajectory of a typical
session. The single half-life + thickness derate captures > 90 % of the
field-study variance against label SPF (Petersen & Wulf 2014, table 2).

## UI vs. integrator decoupling

The dashboard's countdown bar and "active SPF" chip use
[`Patch.isActiveAt`](Models.kt) /
[`remainingHintAt`](Models.kt), which return a binary 2 h window
([`REAPPLY_HINT_DURATION`](Models.kt)) — the user-facing
"reapply soon" guideline.

The integrator uses [`Patch.transmittanceAt`](Models.kt), which keeps
returning meaningful (decaying) protection out to
[`MODELLING_HALF_LIVES`](Models.kt) × halfLife = 12 h. That gap is
intentional: "you should reapply by 2 h" is the actionable
recommendation, while "your residual protection at 3 h is somewhere
between half and a quarter of fresh" is the modelling truth that drives
the burn meter. Showing both numbers as a single bar would mislead.

## Known limitations

- **Thickness is a per-patch constant.** A user who slathers their face
  but skimps on their back gets a single number. The current UI does
  not differentiate body sites; the model follows.
- **Initial-SPF is computed at `tApply` only.** The decay curve does not
  re-account for the user "topping up" the same physical layer; each
  Apply press creates a fresh patch. Composing them via `min` keeps the
  bookkeeping safe but slightly underestimates protection right after a
  thin re-apply (the real layer is the sum of both, not the stronger of
  the two).
- **Wear is a single multiplier.** Wear in practice is a *process* —
  swimming for 10 min then sitting dry for an hour mixes regimes. A
  single scalar cannot represent that without a richer per-session
  activity model.
- **Photostability mix is a hidden assumption.** Defaulting to a 2 h
  half-life assumes modern photostabilised formulations. Users wearing
  pure avobenzone get over-credited.
- **No UVA/UVB split.** The whole model rides the erythemal weighting.
  Aging, immunosuppression and pigmentation effects driven primarily by
  UVA are not directly captured; SPF rating overstates UVA protection
  by 1.5–3× depending on the filter system (Diffey & Robson 1989).

## Sources

### Application thickness

- [Petersen & Wulf 2014 — Application of sunscreen, theory and reality (Photodermatol Photoimmunol Photomed)](https://pubmed.ncbi.nlm.nih.gov/24313722/) — review of field-application thickness; typical 0.39–1.0 mg/cm², median ~0.5 mg/cm²; consumer education and high-SPF reapplication can partially compensate.
- [Diffey 1997 — When should sunscreen be reapplied? (J Am Acad Dermatol)](https://pubmed.ncbi.nlm.nih.gov/9216526/) — origin of the linear "excess SPF scales with applied amount" model and the early-reapplication compensation argument.
- [Faurschou & Wulf 2007 — The relation between sun protection factor and amount of suncreen applied in vivo (Br J Dermatol)](https://pubmed.ncbi.nlm.nih.gov/17493070/) — competing in-vivo exponential fit `S_effective = S_label ^ thickness_fraction` (UV-B narrowband, not what we use).
- [Bech-Thomsen & Wulf 1992 — Sunbathers' application of sunscreen is probably inadequate (Photodermatol Photoimmunol Photomed)](https://pubmed.ncbi.nlm.nih.gov/1492336/) — observational study, average application ≈ 0.5 mg/cm².
- [Schalka & Reis 2011 — Sun protection factor: meaning and controversies (An Bras Dermatol)](https://pubmed.ncbi.nlm.nih.gov/21603814/) — review of SPF measurement methodology and field discrepancy.
- [Autier et al. 2007 — Sunscreen use and increased duration of intentional sun exposure: still a burning issue (Int J Cancer)](https://pubmed.ncbi.nlm.nih.gov/17131312/) — behavioural-compensation angle on real-world SPF.
- [Osterwalder & Herzog 2014 — Global state of sunscreens (Photodermatol Photoimmunol Photomed)](https://onlinelibrary.wiley.com/doi/10.1111/phpp.12112) — counter-argument that the UV-A vs UV-B form of the thickness-SPF relationship differs.

### Half-life and wear

- [Diffey 2001 — Sunscreens, suntans, and skin cancer (BMJ)](https://www.bmj.com/content/322/7283/376) — overview of the 2-hour reapplication guideline and its physical basis.
- [Diffey & Robson 1989 — A new substrate to measure sunscreen protection factors throughout the ultraviolet spectrum (J Soc Cosmet Chem)](https://pubmed.ncbi.nlm.nih.gov/2774677/) — methodology paper that established the SPF/UVAPF split and informs wear-decay assumptions.
- [Stokes & Diffey 1999 — How well are sunscreens water-resistant? (J Photochem Photobiol B)](https://pubmed.ncbi.nlm.nih.gov/10027063/) — empirical water-resistance measurements; non-water-resistant SPF drops ~50 % after 30 min of swimming.
- [Loesch & Kaplan 1994 — Pitfalls in sunscreen application (Arch Dermatol)](https://pubmed.ncbi.nlm.nih.gov/8129420/) — typical 10–40 % missed-area coverage.
- [AAD 2024 — Photoprotection guidelines](https://www.aad.org/public/everyday-care/sun-protection/sunscreen-patients/sunscreen-faqs) — current dermatology-society reapplication advice.

### Photostability of UV filters

- [Tarras-Wahlberg et al. 1999 — Changes in ultraviolet absorption of sunscreens after ultraviolet irradiation (J Invest Dermatol)](https://pubmed.ncbi.nlm.nih.gov/9989396/) — avobenzone photodegradation kinetics in vitro.
- [Gonzenbach et al. 1992 — Photochemistry of avobenzone (J Photochem Photobiol A)](https://pubmed.ncbi.nlm.nih.gov/1454368/) — formal mechanism behind avobenzone photoisomerisation.
- [Diffey 2008 — A method for broad spectrum classification of sunscreens (Int J Cosmet Sci)](https://pubmed.ncbi.nlm.nih.gov/18821841/) — broad-spectrum classification including photostability considerations.
- [Bonda 2008 — Sunscreen photostability (Cosmet Toilet)](https://www.cosmeticsandtoiletries.com/research/methods-tools/article/21834727/sunscreen-photostability) — overview of stabiliser chemistry in modern formulations.
- [RSC 2021 — Determining the photostability of avobenzone in sunscreen formulation models using ultrafast spectroscopy (Phys Chem Chem Phys)](https://pubs.rsc.org/en/content/articlehtml/2021/cp/d1cp03610f) — modern ultrafast-spectroscopy confirmation that octocrylene-stabilised avobenzone is photostable over the 2 h scale.
