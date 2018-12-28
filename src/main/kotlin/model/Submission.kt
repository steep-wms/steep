package model

import helper.UniqueID

data class Submission(
    val id: String = UniqueID.next()
)
