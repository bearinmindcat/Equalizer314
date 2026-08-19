package com.bearinmind.equalizer314

/**
 * Graveyard for retired features, kept for reference only. Nothing in this
 * file is wired up anywhere — it compiles to an empty object and does nothing
 * at runtime. The original code is preserved below as comments, together with
 * where it used to hook in, so it can be resurrected or studied later.
 */
object LegacyFeatures {

    // =====================================================================
    // Per-band L / Both / R tether picker popup (issue #53)
    // Retired 2026-07-09.
    //
    // What it did: while Channel Side EQ was on, tapping an ALREADY-SELECTED
    // band card opened a PopupMenu (Left / Both / Right) anchored to the card
    // that moved the band between the L and R channel EQs ("tether").
    // Superseded by the shared "Both" graph layer — a dedicated flat overlay
    // graph whose bands are summed into both channels' DP output.
    //
    // Where it hooked in:
    //
    // 1) MainActivity — BandToggleManager construction passed the callback:
    //
    //    bandToggleManager = BandToggleManager(
    //        this, bandToggleGroup, bandToggleGroup2, bandToggleExtraRows,
    //        bandToggleExtraScroll, bandAddButtonRow, triangleIndicator,
    //        eqGraphView, stateManager,
    //        onEqChanged, onBandCountChanged, onBandSelected,
    //        onBandReselected = { anchor, bandIdx -> showBandChannelPopup(anchor, bandIdx) }
    //    )
    //
    //    (BandToggleManager.onToggleClicked still checks its onBandReselected
    //    callback, but MainActivity no longer passes one, so it stays null and
    //    re-tapping a selected band card does nothing special.)
    //
    // 2) MainActivity — the two private functions below:
    //
    //    /** Per-band L / Both / R picker (issue #53). Opened by tapping an
    //     *  already-selected band card while Channel Side EQ is on; anchored
    //     *  to that card. The current channel is shown checked. */
    //    private fun showBandChannelPopup(anchor: View, bandIdx: Int) {
    //        if (!eqPrefs.getChannelSideEqEnabled()) return
    //        val current = stateManager.getBandChannel(bandIdx)
    //        val items = listOf(
    //            ParametricEqualizer.Channel.LEFT to "Left",
    //            ParametricEqualizer.Channel.BOTH to "Both",
    //            ParametricEqualizer.Channel.RIGHT to "Right",
    //        )
    //        val popup = android.widget.PopupMenu(this, anchor)
    //        items.forEachIndexed { i, (ch, label) ->
    //            popup.menu.add(0, i, i, label).apply {
    //                isCheckable = true
    //                isChecked = ch == current
    //            }
    //        }
    //        popup.setOnMenuItemClickListener { mi ->
    //            onBandChannelPicked(items[mi.itemId].first)
    //            true
    //        }
    //        popup.show()
    //    }
    //
    //    private fun onBandChannelPicked(channel: ParametricEqualizer.Channel) {
    //        val idx = stateManager.selectedBandIndex ?: return
    //        val movedAway = stateManager.setBandChannel(idx, channel)
    //        if (movedAway) {
    //            // Band left the active channel — refresh everything for the
    //            // (now smaller) active channel and re-highlight.
    //            rebindActiveEq()
    //            reorderToggleRows(animate = false)
    //        } else {
    //            eqGraphView.updateBandLevels()
    //            updateFilterTypeButtons(stateManager.selectedBandIndex)
    //        }
    //        // Update the dotted ghosts (channels may now diverge) and the Both
    //        // button's lit state.
    //        stateManager.getGhostEqs().let { eqGraphView.setGhostEqualizer(it.first, it.second) }
    //        eqGraphView.setOverlayEqualizer(stateManager.getGraphOverlayEq())
    //        paintChannelButtonStyles()
    //    }
    //
    // Note: the underlying tether machinery in EqStateManager
    // (getBandChannel / setBandChannel / syncBothBands / sanitizeTethers) is
    // still live — only this popup UI entry point was retired.
    // =====================================================================

    // =====================================================================
    // Channel Side Options settings section (card + screen entry)
    // Retired 2026-07-14.
    //
    // What it was: a "Channel Side Options" card on the Settings page
    // ("Balance, per-channel EQ, and channel swap") that opened
    // ChannelSideEqActivity — the screen holding the Channel Side EQ enable
    // switch, the balance slider, per-channel gain sliders, and channel swap.
    //
    // Why retired: Channel Side EQ now toggles directly from the power
    // button in the graph's channel popout (the old settings-gear slot,
    // icon @drawable/ic_power_settings_new). The full options screen was a
    // detour for what is a one-tap on/off in practice.
    //
    // What was removed:
    //
    // 1) activity_main.xml — the channelSideEqCard MaterialCardView block
    //    (Settings page, between the Light Theme and Backup & Restore
    //    cards): title "Channel Side Options", subtitle "Balance,
    //    per-channel EQ, and channel swap".
    //
    // 2) MainActivity — the card's click handler:
    //
    //    findViewById<View>(R.id.channelSideEqCard).setOnClickListener {
    //        startActivity(Intent(this, ChannelSideEqActivity::class.java))
    //        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    //    }
    //
    // 3) MainActivity — the old settings-gear handler on the popout
    //    (navigated to the Settings page):
    //
    //    settingsGearBtn.setOnClickListener {
    //        pageEq.visibility = View.GONE
    //        pageSettings.visibility = View.VISIBLE
    //        updateBottomBarHighlight(isEqPage = false)
    //    }
    //
    // Note: ChannelSideEqActivity itself (with the balance slider and
    // per-channel gains) still exists and is registered in the manifest —
    // only its UI entry points were removed. Persisted balance/channel-gain
    // prefs continue to apply to the DP via EqStateManager.
    // =====================================================================

    // =====================================================================
    // Graphic mode tab (main screen)
    // Retired 2026-07-23.
    //
    // What it was: the "Graphic" tab in the main screen's EQ-mode grid
    // (Parametric | Graphic / Table | Simple) — a slider-per-band graphic
    // EQ view driven by GraphicEqController.
    //
    // What was changed (hidden, not deleted):
    //
    // 1) activity_main.xml — modeGraphicBtn got android:visibility="gone";
    //    the button and its styling remain in the layout. Parametric now
    //    fills row 1 via layout_weight.
    //
    // 2) MainActivity — the tab's click listener:
    //
    //    modeGraphicBtn.setOnClickListener {
    //        eqPrefs.saveSimpleEqEnabled(false); switchEqUiMode(EqUiMode.GRAPHIC)
    //    }
    //
    // 3) MainActivity — mode restore maps a persisted GRAPHIC mode to
    //    PARAMETRIC so nobody boots into a tab-less mode.
    //
    // Note: EqUiMode.GRAPHIC, GraphicEqController, and switchEqUiMode's
    // GRAPHIC branch all remain fully functional — un-hiding the button
    // and restoring the listener + removing the restore mapping brings the
    // mode back exactly as it was.
    // =====================================================================

    // =====================================================================
    // Simple mode tab (main screen)
    // Retired 2026-07-23 (same pass as the Graphic tab).
    //
    // What it was: the "Simple" tab in the main screen's EQ-mode grid — a
    // 10-band bars view (SimpleEqController / SimpleEqBarsView) driven by
    // the simpleEqEnabled pref, which also had an override that forced
    // SIMPLE mode at launch and on resume.
    //
    // What was changed (hidden, not deleted):
    //
    // 1) activity_main.xml — modeSimpleBtn got android:visibility="gone";
    //    Table now fills row 2 via layout_weight.
    //
    // 2) MainActivity — the tab's click listener:
    //
    //    modeSimpleBtn.setOnClickListener {
    //        eqPrefs.saveSimpleEqEnabled(true); switchEqUiMode(EqUiMode.SIMPLE)
    //    }
    //
    // 3) MainActivity launch: `effectiveMode = if (getSimpleEqEnabled())
    //    SIMPLE else savedMode` override removed; saved GRAPHIC/SIMPLE modes
    //    map to PARAMETRIC.
    //
    // 4) MainActivity onResume: the simpleEqEnabled two-way sync block
    //    replaced by a one-way "if somehow in SIMPLE, fall back to
    //    Parametric" guard:
    //
    //    val simpleEqEnabled = eqPrefs.getSimpleEqEnabled()
    //    if (simpleEqEnabled && currentEqUiMode != SIMPLE) {
    //        switchEqUiMode(SIMPLE)
    //    } else if (!simpleEqEnabled && currentEqUiMode == SIMPLE) {
    //        switchEqUiMode(savedModeFallback)
    //    }
    //
    // Note: EqUiMode.SIMPLE, SimpleEqController, SimpleEqBarsView, the
    // simpleEqEnabled pref, and switchEqUiMode's SIMPLE branch all remain —
    // un-hide the button, restore the listener and the two override blocks
    // to bring the mode back.
    // =====================================================================

    // =====================================================================
    // Experimental settings lock (settings drawer)
    // Retired 2026-08-19 — extra tap for no real gain; card is now always
    // tappable and the lock ImageButton is hidden (visibility = GONE).
    //
    // What it was: a padlock ImageButton (experimentalLockButton) next to
    // the Experimental card; the card was unclickable + dimmed (alpha 0.6)
    // until the lock was tapped open, and every fresh launch re-locked it.
    //
    // Original MainActivity wiring:
    //
    //    val experimentalLockButton = findViewById<ImageButton>(R.id.experimentalLockButton)
    //    val experimentalCard = findViewById<View>(R.id.experimentalCard)
    //    fun applyExperimentalLockState() {
    //        val unlocked = eqPrefs.getExperimentalUnlocked()
    //        experimentalLockButton.setImageResource(
    //            if (unlocked) R.drawable.ic_lock_open else R.drawable.ic_lock
    //        )
    //        experimentalCard.isClickable = unlocked
    //        experimentalCard.alpha = if (unlocked) 1f else 0.6f
    //    }
    //    applyExperimentalLockState()
    //    experimentalLockButton.setOnClickListener {
    //        val newState = !eqPrefs.getExperimentalUnlocked()
    //        eqPrefs.saveExperimentalUnlocked(newState)
    //        applyExperimentalLockState()
    //    }
    //    experimentalCard.setOnClickListener {
    //        if (!eqPrefs.getExperimentalUnlocked()) return@setOnClickListener
    //        ...startActivity...
    //    }
    //
    // Plus the fresh-launch re-lock in onCreate:
    //    eqPrefs.saveExperimentalUnlocked(false)
    //
    // Note: experimentalLockButton (layout), ic_lock / ic_lock_open, and the
    // saveExperimentalUnlocked/getExperimentalUnlocked prefs all remain —
    // un-hide the button and restore the wiring above to bring it back.
    // =====================================================================
}
