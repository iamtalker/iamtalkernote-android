package kr.iamtalker.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/** 서랍을 여는 가로줄 세 개(☰) */
@Composable
fun HamburgerIcon() {
    val c = MaterialTheme.colorScheme.onSurface
    Column(Modifier.padding(horizontal = 6.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(3) { Spacer(Modifier.width(22.dp).height(2.5.dp).clip(RoundedCornerShape(2.dp)).background(c)) }
    }
}

/** 더보기 메뉴의 세로 점 세 개(⋮) */
@Composable
fun DotsIcon() {
    val c = MaterialTheme.colorScheme.onSurface
    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        repeat(3) { Spacer(Modifier.size(4.5.dp).clip(CircleShape).background(c)) }
    }
}
