[CENTER][IMG]https://i.imgur.com/6J8Q4V2.png[/IMG][/CENTER]

[CENTER][SIZE=6][B]LessLag[/B][/SIZE][/CENTER]
[CENTER][SIZE=4]Server performance guardian for Paper/Spigot (1.20+) with Folia & Luminol support[/SIZE][/CENTER]

[B]LessLag[/B] is a performance intelligence plugin focused on [B]stability first[/B]: detect lag early, explain what is causing it, and apply safe mitigation before TPS collapses.

[HR][/HR]

[SIZE=5][B]Why LessLag?[/B][/SIZE]
[LIST]
[*][B]Actionable diagnostics[/B] instead of blind cleanup
[*][B]Adaptive mitigation[/B] that reacts to real server pressure
[*][B]Modular controls[/B] for entities, redstone, AI, chunks, memory, and farms
[*][B]Low-overhead design[/B] with workload budgeting and on-demand deep stats
[/LIST]

[SIZE=5][B]Core Features[/B][/SIZE]
[LIST]
[*][B]Performance Dashboard[/B] via /lg status: health score, MSPT percentiles (P50/P95/P99), uptime, queue and spike counters
[*][B]Tick Diagnostics[/B] via /lg tickmonitor: distribution, avg/min/max, spike visibility
[*][B]Lag Source Analyzer[/B] via /lg sources and /lg trace: identify hotspots and likely culprits
[*][B]Entity Protection + Smart Cleanup[/B]: protect named/tamed/NPC entities while targeting low-value overload sources
[*][B]Redstone Safeguards[/B]: activation limits, clock detection, piston pressure controls
[*][B]Mob AI Optimization[/B]: frustum/distance-based AI throttling to reduce CPU load
[*][B]Farm Optimizations[/B]: villager optimizer, breeding limiter, density optimizer
[*][B]Predictive Optimization[/B]: MSPT trend detection for proactive responses
[*][B]Web Setup Advisor[/B]: [B]/lg web link[/B] encodes your hardware profile into a URL; the advisor generates a tailored config baseline; [B]/lg apply[/B], [B]/lg verify[/B], and [B]/lg drift[/B] close the apply-verify-drift loop without manual guesswork
[/LIST]

[SIZE=5][B]Useful Admin Commands[/B][/SIZE]
[LIST]
[*][B]/lg status[/B] - Live performance dashboard
[*][B]/lg health[/B] - Health report with recommendation context
[*][B]/lg tickmonitor[/B] - Tick and MSPT distribution diagnostics
[*][B]/lg sources[/B] - Run lag source analysis
[*][B]/lg trace[/B] - Runtime bottleneck summary
[*][B]/lg density[/B] - Density optimizer limits + suppression stats
[*][B]/lg breeding[/B] - Breeding limiter blocked-event stats
[*][B]/lg reload[/B] - Reload configuration without restart
[*][B]/lg web link[/B] - Generate hardware-encoded web Setup Advisor link
[*][B]/lg apply[/B] - Apply web-exported config patch (staged, diff shown first)
[*][B]/lg verify[/B] - Verify live config matches the last apply snapshot
[*][B]/lg drift[/B] - Detect config drift since the last apply snapshot
[/LIST]

[SIZE=5][B]What Makes LessLag Different?[/B][/SIZE]
[LIST]
[*][B]No blind wipes[/B]: prioritizes diagnosis and targeted mitigation first
[*][B]Percentile visibility[/B]: uses MSPT distribution (P50/P95/P99) for realistic performance trends
[*][B]Proactive control[/B]: predictive optimization can trigger before TPS collapse
[*][B]Farm-aware optimization[/B]: villager, breeding, and density systems for real SMP/Skyblock pain points
[*][B]Operator-first workflow[/B]: command surface designed for daily checks and incident response
[/LIST]

[SIZE=5][B]Recommended First 10 Minutes[/B][/SIZE]
[LIST=1]
[*]Run [B]/lg status[/B] and [B]/lg health[/B] to baseline current load
[*]Run [B]/lg tickmonitor[/B] and [B]/lg trace[/B] during peak activity
[*]If farms are heavy, inspect [B]/lg density[/B], [B]/lg breeding[/B], [B]/lg villager[/B]
[*]Tune one config section at a time, then [B]/lg reload[/B]
[*]Re-check percentiles and spike trend before changing more
[/LIST]

[SIZE=5][B]Best-Fit Server Types[/B][/SIZE]
[LIST]
[*]Survival SMP with persistent player bases
[*]Farm-heavy economies / Skyblock
[*]Networks needing stable TPS with low admin noise
[/LIST]

[SIZE=5][B]Compatibility[/B][/SIZE]
[LIST]
[*]Paper/Spigot 1.20+
[*]Folia supported (Folia threaded-region scheduler auto-detected)
[*]Luminol supported (LuminolMC fork auto-detected)
[*]Compatibility toggles for Pufferfish DAB, ClearLag, and MobFarmManager
[/LIST]

[SIZE=5][B]Permissions[/B][/SIZE]
[LIST]
[*][B]lesslag.admin[/B] - Full command access
[*][B]lesslag.notify[/B] - Receive performance alerts
[*][B]lesslag.setup[/B] - Setup Advisor access
[/LIST]

[SIZE=5][B]Install[/B][/SIZE]
[LIST=1]
[*]Drop LessLag.jar into /plugins
[*]Restart server
[*]Tune plugins/LessLag/config.yml to your needs
[*]Use /lg status and /lg setup for baseline verification
[/LIST]

[SIZE=5][B]Performance Philosophy[/B][/SIZE]
[LIST]
[*]Telemetry counters piggyback existing event/tick paths
[*]Deep calculations (like percentiles) are command-time, on-demand
[*]Heavy analysis is workload-budgeted to reduce tick impact
[/LIST]

[SIZE=5][B]Website[/B][/SIZE]
[URL]https://lesslag-web.vercel.app[/URL]

[SIZE=5][B]Support / Feedback[/B][/SIZE]
Open an issue with your timings/profile context and LessLag command output for faster diagnosis.
