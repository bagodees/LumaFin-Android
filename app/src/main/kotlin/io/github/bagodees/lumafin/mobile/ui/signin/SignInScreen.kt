package io.github.bagodees.lumafin.mobile.ui.signin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.bagodees.lumafin.mobile.R
import io.github.bagodees.lumafin.mobile.ui.theme.LumaFinColors

@Composable
fun SignInScreen(viewModel: SignInViewModel = viewModel()) {
	val step by viewModel.step.collectAsState()
	BackHandler(enabled = step !is SignInStep.ChooseServer) { viewModel.back() }

	Box(
		contentAlignment = Alignment.TopCenter,
		modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().imePadding(),
	) {
		// One readable column on every screen size; wide screens get margins, not stretched fields
		Column(
			horizontalAlignment = Alignment.CenterHorizontally,
			modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
		) {
			// The launcher icon is an adaptive icon, which Compose can't draw; its artwork sits on the brand's black
			Image(
				painterResource(R.drawable.app_icon_foreground),
				contentDescription = null,
				modifier = Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)).background(androidx.compose.ui.graphics.Color.Black),
			)
			Spacer(Modifier.height(16.dp))
			when (val current = step) {
				is SignInStep.ChooseServer -> ServerStep(current, viewModel::connect)
				is SignInStep.ChooseUser -> UserStep(current, viewModel)
				is SignInStep.Password -> PasswordStep(current, viewModel)
				is SignInStep.QuickConnect -> QuickConnectStep(current, viewModel)
			}
		}
	}
}

@Composable
private fun Heading(text: String, detail: String? = null) {
	Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
	if (detail != null) {
		Spacer(Modifier.height(8.dp))
		Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
	}
	Spacer(Modifier.height(24.dp))
}

@Composable
private fun ServerStep(step: SignInStep.ChooseServer, onConnect: (String) -> Unit) {
	var address by rememberSaveable(step.address) { mutableStateOf(step.address) }
	Heading(stringResource(R.string.signin_welcome), stringResource(R.string.signin_server_hint))
	OutlinedTextField(
		value = address,
		onValueChange = { address = it },
		label = { Text(stringResource(R.string.signin_server_address)) },
		supportingText = { Text(stringResource(if (step.failed) R.string.signin_server_failed else R.string.signin_server_example)) },
		isError = step.failed,
		singleLine = true,
		enabled = !step.busy,
		keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
		keyboardActions = KeyboardActions(onGo = { onConnect(address) }),
		modifier = Modifier.fillMaxWidth(),
	)
	Spacer(Modifier.height(16.dp))
	Button(onClick = { onConnect(address) }, enabled = !step.busy && address.isNotBlank(), modifier = Modifier.fillMaxWidth().height(48.dp)) {
		if (step.busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
		else Text(stringResource(R.string.signin_connect))
	}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UserStep(step: SignInStep.ChooseUser, viewModel: SignInViewModel) {
	Heading(stringResource(R.string.signin_who), step.server.name)
	FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(20.dp)) {
		step.users.forEach { user -> UserTile(user.name.orEmpty()) { viewModel.chooseUser(step.server, user) } }
		UserTile(stringResource(R.string.signin_other_user), initials = "+") { viewModel.chooseUser(step.server, null) }
	}
	Spacer(Modifier.height(28.dp))
	OutlinedButton(onClick = { viewModel.startQuickConnect(step.server) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
		Text(stringResource(R.string.signin_quick_connect))
	}
	TextButton(onClick = viewModel::back) { Text(stringResource(R.string.signin_change_server)) }
}

@Composable
private fun UserTile(name: String, initials: String = name.take(1).uppercase(), onClick: () -> Unit) {
	Column(
		horizontalAlignment = Alignment.CenterHorizontally,
		modifier = Modifier.width(88.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
	) {
		Box(contentAlignment = Alignment.Center, modifier = Modifier.size(72.dp).clip(CircleShape).background(LumaFinColors.Raised)) {
			Text(initials, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = LumaFinColors.Cyan)
		}
		Spacer(Modifier.height(8.dp))
		Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
	}
}

@Composable
private fun PasswordStep(step: SignInStep.Password, viewModel: SignInViewModel) {
	var username by rememberSaveable(step.server.url) { mutableStateOf(step.username) }
	var password by rememberSaveable(step.server.url, step.username) { mutableStateOf("") }
	Heading(if (step.fixedUser) step.username else stringResource(R.string.signin_sign_in), step.server.name)
	if (!step.fixedUser) {
		OutlinedTextField(
			value = username,
			onValueChange = { username = it },
			label = { Text(stringResource(R.string.signin_username)) },
			singleLine = true,
			enabled = !step.busy,
			keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
			modifier = Modifier.fillMaxWidth(),
		)
		Spacer(Modifier.height(12.dp))
	}
	OutlinedTextField(
		value = password,
		onValueChange = { password = it },
		label = { Text(stringResource(R.string.signin_password)) },
		supportingText = if (step.failed) ({ Text(stringResource(R.string.signin_failed)) }) else null,
		isError = step.failed,
		singleLine = true,
		enabled = !step.busy,
		visualTransformation = PasswordVisualTransformation(),
		keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
		keyboardActions = KeyboardActions(onDone = { viewModel.signIn(step.server, username, password) }),
		modifier = Modifier.fillMaxWidth(),
	)
	Spacer(Modifier.height(16.dp))
	Button(
		onClick = { viewModel.signIn(step.server, username, password) },
		enabled = !step.busy && username.isNotBlank(),
		modifier = Modifier.fillMaxWidth().height(48.dp),
	) {
		if (step.busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
		else Text(stringResource(R.string.signin_sign_in))
	}
	OutlinedButton(onClick = { viewModel.startQuickConnect(step.server) }, modifier = Modifier.padding(top = 8.dp).fillMaxWidth().height(48.dp)) {
		Text(stringResource(R.string.signin_quick_connect))
	}
	TextButton(onClick = viewModel::back) { Text(stringResource(R.string.back)) }
}

@Composable
private fun QuickConnectStep(step: SignInStep.QuickConnect, viewModel: SignInViewModel) {
	Heading(
		stringResource(R.string.signin_quick_connect),
		stringResource(if (step.unavailable) R.string.signin_quick_connect_unavailable else R.string.signin_quick_connect_hint),
	)
	if (!step.unavailable) {
		if (step.code == null) CircularProgressIndicator()
		else {
			Text(
				step.code,
				fontSize = 44.sp,
				fontWeight = FontWeight.Bold,
				letterSpacing = 8.sp,
				color = LumaFinColors.Cyan,
				modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(LumaFinColors.Raised).padding(horizontal = 28.dp, vertical = 16.dp),
			)
			Spacer(Modifier.height(16.dp))
			Text(stringResource(R.string.signin_quick_connect_waiting), color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
	}
	Spacer(Modifier.height(16.dp))
	TextButton(onClick = viewModel::back) { Text(stringResource(R.string.back)) }
}
