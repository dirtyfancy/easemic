package com.easemic.app.sync

/** Keeps a bucket's local repair journal attached when callers construct fresh collection stores. */
class SeriesIndexObjectStore(private val delegate: ObjectStore, val journal: SeriesIndexJournal) : ObjectStore by delegate
