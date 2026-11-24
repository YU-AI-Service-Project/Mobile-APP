package com.wattup.app

import android.content.Context

internal class NearbySource(context: Context) : StationSource by HttpStationSource(context.getString(R.string.wattup_api_base_url))
