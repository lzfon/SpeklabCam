package com.spektralab.android.engine.host

internal object RenderHostProtocol {
    const val START = 1
    const val CANCEL = 2
    const val STARTED = 3
    const val PROGRESS = 4
    const val COMPLETE = 5
    const val FAILED = 6
    const val ABORTING = 7
    const val RELEASE_DEVICE = 8
    const val DEVICE_RELEASING = 9
    const val DEVICE_RELEASED = 10
    const val ENCODE = 11
    const val COMPOSE_TABLE = 12
    const val TABLE_SOURCES = "table_sources"
    const val WIDTH = "width"
    const val HEIGHT = "height"
    const val OFFSET = "offset"
    const val METADATA = "metadata"
    const val JOB = "job"
    const val REQUEST = "request"
    const val DESCRIPTOR = "descriptor"
}
