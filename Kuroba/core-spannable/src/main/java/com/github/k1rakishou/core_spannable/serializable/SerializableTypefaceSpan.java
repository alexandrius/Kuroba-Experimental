package com.github.k1rakishou.core_spannable.serializable;

import com.github.k1rakishou.common.DoNotStrip;
import com.google.gson.annotations.SerializedName;

@DoNotStrip
public class SerializableTypefaceSpan {
    @SerializedName("family")
    private String family;

    public SerializableTypefaceSpan(String family) {
        this.family = family;
    }

    public String getFamily() {
        return family;
    }
}
