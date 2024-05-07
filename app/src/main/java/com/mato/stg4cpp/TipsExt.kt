package com.mato.stg4cpp

import android.util.Log

const val TAG = "crowforkotlin"

fun Any?.info() {
    Log.d(TAG, this.toString())
}