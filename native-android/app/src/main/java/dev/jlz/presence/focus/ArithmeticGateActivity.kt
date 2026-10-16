package dev.jlz.presence.focus

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jlz.presence.launcher.LauncherRepository
import dev.jlz.presence.ui.theme.IceCrystalTheme

class ArithmeticGateActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra("package_name")?.takeIf { it.isNotBlank() }
        if (pkg == null || pkg == packageName ||
            LocalAppClassifier(this).arithmeticExempt(pkg)) {
            finish(); return
        }
        val kind = ArithmeticGatePolicy.kind(EntertainmentPolicy.profile(pkg),
            LocalAppClassifier(this).classify(pkg))
        val name = runCatching {
            EntertainmentPolicy.profile(pkg)?.appName ?: packageManager
                .getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault("这个应用")
        val questions = ArithmeticGatePolicy.questions(kind,
            (System.currentTimeMillis() xor pkg.hashCode().toLong()).toInt())
        setContent {
            IceCrystalTheme {
                QuizScreen(name, questions, onLeave = { finish() }, onPass = {
                    val repository = ArithmeticGateRepository(this)
                    repository.passed(pkg, kind, System.currentTimeMillis())
                    if (LauncherRepository(this).launchApp(pkg)) finish()
                    else repository.revoke(pkg)
                })
            }
        }
    }

    companion object {
        fun show(context: Context, pkg: String) {
            context.startActivity(Intent(context, ArithmeticGateActivity::class.java)
                .putExtra("package_name", pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
    }
}

@Composable
private fun QuizScreen(appName: String, questions: List<ArithmeticQuestion>,
                       onLeave: () -> Unit, onPass: () -> Unit) {
    var index by remember { mutableIntStateOf(0) }
    var value by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf("") }
    BackHandler { onLeave() }
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF1F0FA)) {
        Column(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color(0xFFE9E4F5), Color(0xFFF8F6FC), Color(0xFFE3EDF4)))
            ).padding(horizontal = 28.dp, vertical = 42.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("WORLD BETWEEN · 小小门槛", color = Color(0xFF71658F),
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(24.dp))
            Text("先和我算完，再去$appName。", color = Color(0xFF302849),
                fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text("${index + 1} / ${questions.size} · 全部答对才放行，错了可以重试这一题。",
                color = Color(0xFF665D78), fontSize = 14.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(28.dp))
            LinearProgressIndicator(progress = { index.toFloat() / questions.size },
                modifier = Modifier.fillMaxWidth(), color = Color(0xFFAA8ACB),
                trackColor = Color(0xFFDAD4E7))
            Spacer(Modifier.height(28.dp))
            Surface(shape = RoundedCornerShape(28.dp), color = Color.White.copy(alpha = 0.86f),
                shadowElevation = 8.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(questions[index].text, fontSize = 38.sp,
                        color = Color(0xFF2F2946), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(22.dp))
                    OutlinedTextField(value = value, onValueChange = { input ->
                        if (input.length <= 5 && input.all(Char::isDigit)) { value = input; feedback = "" }
                    }, label = { Text("写下答案") }, singleLine = true,
                        textStyle = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Medium),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFF2F2946),
                            unfocusedTextColor = Color(0xFF2F2946),
                            focusedContainerColor = Color(0xFFFAF8FD),
                            unfocusedContainerColor = Color(0xFFFAF8FD),
                            cursorColor = Color(0xFF2F2946),
                            focusedLabelColor = Color(0xFF514366),
                            unfocusedLabelColor = Color(0xFF514366),
                            focusedBorderColor = Color(0xFF66507F),
                            unfocusedBorderColor = Color(0xFF81718F)
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth())
                    if (feedback.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(feedback, color = Color(0xFF9B5D75), fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = {
                        if (value.toIntOrNull() == questions[index].answer) {
                            if (index == questions.lastIndex) onPass()
                            else { index++; value = ""; feedback = "答对了，继续。" }
                        } else { value = ""; feedback = "这题不对。慢一点，重新算。" }
                    }, enabled = value.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFAA8ACB)),
                        shape = RoundedCornerShape(18.dp)) {
                        Text(if (index == questions.lastIndex) "算完就进去" else "核对这一题")
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = onLeave) { Text("先不打开", color = Color(0xFF6A5D82)) }
            }
        }
    }
}
