# Rule UI and operation workflow review (2026-10-18)

The overview now orders Hosts state, rule counts, synchronization and the optional Web Server control. Applied/state LiveData is observed continuously. Rule totals are labelled as rules, distinct from request counters. Request counters keep Long values and the block rate includes its percentage unit. Large text affects the counter layout breakpoint.

Rule synchronization has a disabled running action and persistent phase results. It distinguishes source retrieval, local save, Hosts application and server refresh failures. A stopped optional server does not make a Hosts sync fail; a reachable server must acknowledge a reload. No subscription URL or raw exception is shown in the new status text.

Local rule add/remove and source enable/remove now use `SourceModel.editRules`: under the source lock, the mutation, snapshot validation and effective Hosts index rebuild share a database transaction, followed by immutable publication. This closes the offline gap where a list changed but the actual rules waited for the next successful subscription download. Personal rules validate names and replacement addresses, start enabled, and replace prior personal actions for the same normalized name. Hosts application and server export are subsequent phases; an application failure explicitly reports saved rules without claiming Hosts success.

The rules page exposes source management above the list, shows matching scope per row, retains current search/classification, and invalidates list results after updates. A subscription rule offers source management rather than a delete icon that only hid the row. Local-source add failures preserve form input and show inline feedback. Search no-results and an empty category use different messages. Root Hosts still covers known names only.

Verification uses actual production Compose components with deterministic data in `RuleWorkflowUiTest`, without launching the first-run MainActivity flow. Screenshots are stored through MediaStore on the disposable API 35 emulator, so AGP test-package cleanup does not remove them. Interaction checks exercise busy/retry actions and subscription management labels; screenshots cover light and dark/large text. `SourceLoaderRoomTest` adds local edit/override/removal, offline source disable and invalid edit rollback using a private database on API 30 and 35.

Screenshots do not establish real Root availability, device filtering, or recovery acceptance. APKs remain CI inspection artifacts; no release, merge, daily-device install or network capture activation is part of this UI change.


## Browsing follow-up

The rules browser now queries original entries joined to their source state. Search never groups different sources/scopes by hostname, and enabled/disabled labels reflect both the item and source flag. All/enabled/disabled/personal filters use the same predicate for row and count queries. Searches are literal case-insensitive substrings rather than SQL wildcard expansion. Stable 300-entry pages let users reach later rules without increasing the import or per-page bounds; count, page clamping and rows share one Room read transaction. Cancelled queries cannot overwrite a new page or its loading state. A read failure has a retry action and is distinct from an empty result.

Room browser tests cover duplicate names with different scopes/sources, legacy source/item state differences, pagination boundaries, literal percent/underscore queries, and source disable/delete. Component tests cover filter selection, paging boundaries, disabled labels and screenshots. Large-text screenshot evidence includes the action after scrolling.

## Full rule inspection

Tapping a rule row opens a read-only, scrollable detail dialog. It retains the complete domain and replacement address, shows action/scope/source and the combined entry/source enabled state, and offers explicit full-domain copying. All detail text remains selectable. Subscription rules retain their source-management action; personal rules do not show a subscription action. Existing delete controls remain separate from inspecting a row. Detail status describes the entry, not a claim that it is the winning DNS decision or has been applied through Root.

Production-component checks verify inspection does not delete a rule, the complete host reaches the copy callback, source management remains available, and a personal detail dialog scrolls with 1.6x text in the dark theme. Screenshots include both the initial details and the scrolled copy action.
