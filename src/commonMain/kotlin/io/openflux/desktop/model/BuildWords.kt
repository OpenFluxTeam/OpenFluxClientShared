package io.openflux.desktop.model

/*
 * The few words of the OpenFlux service that sit in enums, which a build cannot leave out as it leaves out
 * screens (see io.openflux.app.shell.BuildExtras). A full build names them; the App Store build has "" there,
 * so that its binary carries no word of a paid service.
 */

/** ProfileSource.Subscription, and the list's group of its locations. */
expect val SUBSCRIPTION_SOURCE_LABEL: String

/** ProfileSource.Bot. */
expect val BOT_SOURCE_LABEL: String

/** The account's page in Settings and its entry under ＋. */
expect val ACCOUNT_TITLE: String

/** The page of experimental features in Settings. */
expect val EXPERIMENTAL_TITLE: String

/** The ＋ entry that reads what was pasted: a key of a subscription too where there is one. */
expect val KEY_CHOICE_TITLE: String
