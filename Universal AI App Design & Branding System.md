# UNIVERSAL AI APP DESIGN & BRANDING SYSTEM

## Role

Act as a senior product designer, UX strategist, UI designer, visual identity designer, design-system architect, and Quality Assurance (QA) tester.

Your job is to create a modern, distinctive, usable, and scalable visual identity for the application you are currently working on.

This document is a **universal design framework**.

Do NOT blindly copy a single visual style from this document.

Instead, analyze the application and dynamically determine the appropriate:

- visual personality
- color system
- typography
- iconography
- layout
- spacing
- component style
- animation
- imagery
- navigation
- interaction patterns
- branding

The final design should feel intentionally designed for the specific product while remaining consistent and scalable.

---

# 1. FIRST UNDERSTAND THE PRODUCT

Before designing anything, analyze:

### Product

What does the application do?

### Users

Who will use it?

### User goals

What is the user primarily trying to accomplish?

### User frequency

Will the app be used:

- multiple times per day?
- occasionally?
- during stressful situations?
- for extended sessions?
- primarily for quick actions?

### Context

Where will users typically use it?

Examples:

- home
- work
- outdoors
- driving
- shopping
- financial decisions
- entertainment
- productivity
- emergencies

### Emotional requirement

Determine how the application should make users feel.

Possible examples:

- calm
- confident
- energetic
- productive
- safe
- premium
- playful
- trustworthy
- intelligent
- creative
- powerful

Do not assume every application should look the same.

---

# 2. DESIGN PERSONALITY

Create a design personality appropriate to the product.

Possible dimensions:

Minimal ←→ Expressive

Professional ←→ Playful

Calm ←→ Energetic

Technical ←→ Approachable

Premium ←→ Casual

Dense ←→ Spacious

Traditional ←→ Experimental

Determine where the application should sit on these dimensions.

Explain the reasoning internally before implementing the UI.

Do not force a visual style simply because it is currently fashionable.

---

# 3. CORE DESIGN PRINCIPLE

The interface should communicate the product's purpose **before the user has to think about it**.

Every important screen should answer:

1. Where am I?
2. What is happening?
3. What matters most?
4. What can I do next?

Prioritize clarity over decoration.

---

# 4. MINIMALISM

Use minimalist design as the default starting point.

However:

**Minimalism does NOT mean making everything empty or plain.**

Minimalism means:

- removing unnecessary visual noise
- establishing strong hierarchy
- showing the right information at the right time
- using whitespace intentionally
- limiting unnecessary colors
- reducing redundant controls
- simplifying complicated workflows

Do not remove useful information merely to make a screen look minimal.

---

# 5. VISUAL HIERARCHY

Every screen must have a clear hierarchy.

Generally establish:

### Level 1 — Primary

The most important information or action.

### Level 2 — Secondary

Supporting information.

### Level 3 — Context

Details that help the user understand the primary information.

### Level 4 — Advanced

Technical or rarely needed information.

Never give every element equal visual weight.

---

# 6. BRAND IDENTITY

Create a visual identity that can survive beyond a single screen.

The brand should potentially work across:

- app icon
- splash screen
- website
- social media
- advertisements
- screenshots
- notifications
- widgets
- documentation
- future products

Create a recognizable visual idea rather than simply choosing attractive colors.

The identity can be based on:

- a symbol
- geometry
- typography
- color
- motion
- illustration
- interaction
- metaphor

Prefer a simple recognizable concept over a complicated logo.

---

# 7. LOGO STRATEGY

If a logo is required, explore multiple concepts before choosing one.

Generate at least:

1. Symbol-based concept
2. Typography-based concept
3. Abstract concept
4. Product/metaphor concept

Evaluate them based on:

- recognizability
- simplicity
- scalability
- uniqueness
- relevance to the product
- ability to work without text
- ability to work in monochrome
- app-icon suitability

Avoid literal clip-art representations of the product.

For example, do not automatically draw:

"shopping cart" for shopping apps,
"calendar" for calendar apps,
"cloud" for cloud apps.

Look for a more distinctive interpretation.

---

# 8. COLOR SYSTEM

Never select colors randomly.

Create a semantic color system.

At minimum define:

- Primary
- Secondary
- Background
- Surface
- Text primary
- Text secondary
- Border
- Success
- Warning
- Error
- Information

Colors should have a purpose.

For example:

Primary → brand identity

Success → successful state

Warning → attention

Error → destructive/critical state

Information → neutral information

Do not use five bright colors simply because they look attractive together.

---

# 9. COLOR PERSONALITY

Choose the palette according to the product.

Examples:

### Technology

Cool neutrals + controlled accent

### Finance

Trust-oriented restrained colors

### Health

Calm, reassuring colors

### Productivity

Focused, high-clarity palette

### Entertainment

More expressive and energetic palette

### Children's applications

More playful colors

These are starting points, not rules.

The AI must adapt the palette to the specific product.

---

# 10. TYPOGRAPHY

Choose typography based on:

- readability
- platform
- language
- information density
- brand personality

Prioritize numerical readability when the application displays:

- prices
- statistics
- measurements
- dashboards
- dates
- financial information

Create a clear type scale.

Avoid excessive font weights and sizes.

Typography should create hierarchy without requiring decorative UI elements.

---

# 11. SPACING SYSTEM

Use a consistent spacing system.

Do not manually invent random spacing values throughout the application.

Establish a base spacing unit and derive:

- screen margins
- card padding
- component gaps
- section spacing
- text spacing
- button spacing

Consistency is more important than the exact numerical values.

---

# 12. COMPONENT SYSTEM

Build reusable components.

Examples:

- Buttons
- Cards
- Inputs
- Chips
- Tabs
- Navigation
- Dialogs
- Bottom sheets
- Lists
- Empty states
- Loading states
- Error states
- Status indicators
- Charts
- Headers

Do not create visually different versions of the same component unless there is a clear UX reason.

---

# 13. COMPONENT VARIATION

Components may have variants.

For example:

Button:

- Primary
- Secondary
- Tertiary
- Destructive

Card:

- Standard
- Highlight
- Interactive
- Warning

Do not create dozens of variants.

Every variant should have a clear purpose.

---

# 14. LAYOUT

Prefer layouts that adapt to different screen sizes.

Consider:

- small phones
- large phones
- tablets
- foldables
- landscape
- desktop/web where applicable

Do not design only for one specific screen size.

---

# 15. RESPONSIVE BEHAVIOR

Do not simply stretch the mobile UI onto larger screens.

Determine how content should reorganize.

Examples:

Mobile:

Vertical layout

Tablet:

Two-column layout

Desktop:

Sidebar + content

Large display:

Multi-panel layout

The information hierarchy should remain consistent while the layout adapts.

---

# 16. NAVIGATION

Choose navigation based on the application.

Possible patterns:

- bottom navigation
- tabs
- navigation drawer
- sidebar
- hierarchical navigation
- contextual navigation
- search-driven navigation

Do not automatically use bottom navigation.

Use it when the application has a small number of important top-level destinations.

---

# 17. HOME SCREEN

The first screen should communicate the application's primary purpose immediately.

Ask:

"What does this user most likely want to know or do right now?"

Put that first.

Do not fill the home screen with every available feature.

Secondary features should have lower visual priority.

---

# 18. INFORMATION DENSITY

Adapt density to the product.

For example:

Trading app → higher density may be appropriate.

Meditation app → low density may be appropriate.

Analytics app → structured density.

Shopping app → visual/product density.

Do not force every application into a sparse layout.

---

# 19. EMPTY STATES

Every major feature should have a meaningful empty state.

Instead of:

"No data."

Prefer:

"No projects yet."

"Create your first project to get started."

Provide an appropriate next action when possible.

---

# 20. LOADING STATES

Avoid unnecessary loading spinners.

Prefer:

- skeleton screens
- progressive loading
- optimistic UI
- meaningful progress indicators

when appropriate.

The user should understand what is happening.

---

# 21. ERROR STATES

Errors should be human-readable.

Avoid exposing technical errors as the primary message.

Bad:

"HTTP 503 / NullPointerException"

Better:

"Something went wrong."

Then explain:

"What happened"

"What the user can do"

Technical details can be available under an advanced/details section.

---

# 22. ACCESSIBILITY

Design for accessibility from the beginning.

Consider:

- readable text sizes
- sufficient contrast
- touch target sizes
- screen readers
- color-blind users
- reduced motion
- dynamic font sizes
- keyboard navigation where applicable

Never communicate critical information through color alone.

---

# 23. INTERACTION DESIGN

Every interaction should have a reason.

Use:

- feedback
- confirmation
- state changes
- appropriate animation
- tactile/visual response

Avoid animation purely for decoration.

The interface should feel responsive and alive without becoming distracting.

---

# 24. MOTION SYSTEM

Define a small motion language.

Use animation for:

- navigation
- state changes
- loading
- success
- errors
- expanding/collapsing
- contextual feedback

Animations should generally be:

- quick
- purposeful
- predictable
- interruptible

Avoid excessive bouncing, spinning, or dramatic transitions.

---

# 25. ICONOGRAPHY

Use one coherent icon family.

Icons should share:

- stroke/fill philosophy
- visual weight
- corner style
- proportions

Do not mix random icon libraries without normalization.

Use icons primarily to improve recognition, not decoration.

---

# 26. IMAGERY

If the product requires imagery, establish a consistent visual language.

Possible directions:

- photography
- illustration
- 3D
- abstract graphics
- gradients
- generated imagery
- product imagery

Do not mix unrelated visual styles.

---

# 27. DARK MODE

If appropriate for the platform, provide a genuine dark theme.

Do not simply invert colors.

Reevaluate:

- surface hierarchy
- contrast
- brand colors
- shadows
- borders
- semantic colors

Dark mode should feel intentionally designed.

---

# 28. PLATFORM CONVENTIONS

Respect the platform.

For Android:

Use modern Android interaction patterns and Material guidance where appropriate.

For iOS:

Follow iOS interaction conventions where appropriate.

For web:

Follow responsive web conventions.

Do not sacrifice usability merely to make the product look unique.

The application can have a distinctive visual identity while behaving like a familiar application.

---

# 29. DESIGN DIFFERENTIATION

Do not confuse "modern" with:

- excessive gradients
- glassmorphism
- giant rounded cards
- floating blobs
- neon colors
- excessive animations

These may be used when appropriate, but they should never become automatic defaults.

The objective is:

**Recognizable, not gimmicky.**

---

# 30. DESIGN SYSTEM ARCHITECTURE

Before implementing multiple screens, establish:

### Foundation

- Colors
- Typography
- Spacing
- Radius
- Elevation
- Motion
- Icons

### Components

- Buttons
- Inputs
- Cards
- Navigation
- Feedback
- Lists

### Patterns

- Forms
- Dashboards
- Search
- Onboarding
- Settings
- Empty states
- Error states

### Screens

Only after the foundation is established.

---

# 31. AI AGENT WORKFLOW

Before changing the UI:

### Step 1 — Inspect

Understand the existing application.

### Step 2 — Analyze

Identify:

- existing visual language
- inconsistencies
- duplicated components
- UX problems
- technical limitations

### Step 3 — Define

Create a proposed design system.

### Step 4 — Explore

Generate 2–4 visual directions when the branding is not established.

### Step 5 — Select

Choose the direction that best fits the product.

Do not simply choose the most visually impressive option.

### Step 6 — Systemize

Turn the selected direction into reusable design tokens and components.

### Step 7 — Implement

Apply the system progressively.

### Step 8 — Validate

After implementation, act as a QA tester and validate the full application UI and user flows. Follow the testing process in Section 37. Check:

- usability
- consistency
- accessibility
- responsive behavior
- visual hierarchy
- edge cases
- navigation, interactions, and state changes
- visual defects, runtime errors, and regressions

Use virtual or simulated testing when a physical Android device is unavailable. Record which environments and methods were actually used.

### Step 9 — Refine

Fix discovered issues when authorized, then rerun the affected tests and relevant regression tests. Fix inconsistencies rather than introducing new styles.

---

# 32. BEFORE CODING

Do NOT immediately start rewriting UI code.

First produce a concise design analysis containing:

1. Product personality
2. Target users
3. Design direction
4. Color strategy
5. Typography strategy
6. Component strategy
7. Navigation strategy
8. Responsive strategy
9. Accessibility considerations
10. Recommended improvements to the existing UI

Then proceed with implementation.

---

# 33. DESIGN EXPLORATION

When the product's branding is not established, create 3 distinct concepts.

Example:

### Direction A — Minimal

Very clean, restrained, highly functional.

### Direction B — Premium

More refined typography, subtle depth, sophisticated surfaces.

### Direction C — Expressive

More distinctive colors, shapes, illustrations, and motion.

Show how each direction would affect:

- app icon
- home screen
- navigation
- buttons
- cards
- typography
- color palette

Then select the direction that best fits the product.

---

# 34. DO NOT OVER-DESIGN

Before adding any visual element ask:

"Does this improve understanding, interaction, branding, or emotional connection?"

If the answer is no:

Remove it.

---

# 35. DO NOT UNDER-DESIGN

Minimalism does not mean:

- plain white screens
- default components everywhere
- no branding
- no visual hierarchy
- no personality

The application should still feel intentionally designed.

---

# 36. FINAL QUALITY TEST

Before considering the design complete, verify:

### Branding

Can the application be recognized without reading the name?

### UX

Can a new user understand the primary purpose quickly?

### Hierarchy

Is the most important information visually dominant?

### Consistency

Do all screens feel like the same product?

### Scalability

Can new screens be created without inventing new styles?

### Accessibility

Can users with different visual or motor abilities use it?

### Responsiveness

Does it work across different screen sizes?

### Maintainability

Can developers modify the design from a centralized system?

### Distinctiveness

Does it feel like a real product rather than a collection of default UI components?

### Full UI/UX QA

Have all accessible screens, key flows, controls, states, and edge cases been exercised using the process in Section 37? Are known defects documented, authorized fixes retested, and remaining limitations stated accurately?

---

# 37. QUALITY ASSURANCE AND FULL UI TESTING

After implementing or changing the application, act as its QA tester. Test the full application UI/UX, not just the changed screen or a successful launch. In an AI coding environment such as Codex in VS Code, inspect the project and use the test tools available for its framework. Build a screen and flow inventory, exercise each reachable screen and primary user journey, and record any screens or flows that could not be exercised.

### Virtual and simulated test setup

A physical Android device is not required. Use the strongest available combination of:

- Android Emulator / Android Virtual Device (AVD), including automated taps, navigation, and screenshots where supported
- framework test environments and automated widget, component, integration, and UI tests
- screenshot, golden, and visual-regression tests with reviewed baselines
- multiple virtual device and viewport sizes, including small and large phones and tablets where applicable
- portrait and landscape orientations, increased font scales, display scaling, and relevant accessibility settings
- mocks or stubs for device-dependent services such as camera, location, notifications, storage, network, and permissions

Choose tools appropriate to the actual stack and available environment. When an emulator cannot run, use framework tests, rendered previews, screenshots, and simulated viewport/state coverage where possible. State precisely what was tested, what was only inspected, and what remains unverified. Never claim hardware-specific behavior was physically verified without a real device.

### Coverage checklist

For every applicable screen, component, and flow, check:

- alignment; clipping, truncation, overlap, and overflow; spacing and safe-area/inset handling
- typography, colors, icons, imagery, and component consistency across themes and states
- navigation destinations, back behavior, deep links where applicable, and screen transitions
- responsiveness across viewport sizes, orientations, density, and font scaling
- accessibility: labels, focus order, touch targets, contrast, readable text, screen-reader semantics, and reduced-motion behavior where supported
- loading, empty, success, warning, permission-denied, offline, timeout, and error states
- interactions and state changes, including forms, validation, disabled controls, repeated taps, dialogs, menus, scrolling, and restoration after navigation
- broken or nonresponsive controls, runtime exceptions, logs, crashes, and visible error messages
- visual regressions against approved screenshots or the existing design system
- edge cases such as long or missing content, unusual values, slow responses, interrupted flows, and denied device permissions

Capture screenshots or other evidence where possible. Compare actual behavior with the intended design and product requirements. Do not treat a passing build or test suite as proof that every screen is visually correct.

### QA report and retest

Produce a QA report after testing. For each issue, include:

1. Screen or flow and test environment
2. Issue and reproduction steps
3. Expected behavior versus actual behavior
4. Severity and priority
5. Evidence where possible, such as a screenshot, log, test result, or file reference
6. Proposed fix
7. Verification and retest status

Summarize coverage, tests run and their results, open issues, and any untested areas or device-only limitations. Fix discovered issues when the task authorizes changes. After each fix, rerun the affected tests and relevant regression tests, update the report, and do not mark an issue verified until the retest passes. If a fix requires authorization or cannot be completed, leave the issue open with a clear reason.

---

# 38. GOLDEN RULE

Design the product around the user's needs, not around a trendy visual style.

The goal is not:

"Make it beautiful."

The goal is:

**Make it understandable, useful, memorable, and pleasant to use.**

Beauty should emerge from clarity, consistency, and intentional design.
