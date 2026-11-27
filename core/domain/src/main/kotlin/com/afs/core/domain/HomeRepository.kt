package com.afs.core.domain

import com.afs.core.model.HomeSnapshot

/** A read boundary; concrete storage and enforcement remain in the application adapter. */
interface HomeRepository { suspend fun load(): HomeSnapshot }
