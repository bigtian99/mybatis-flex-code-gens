package club.bigtian.mf.plugin.action.flex;

import club.bigtian.mf.plugin.core.service.LoginService;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import org.jetbrains.annotations.NotNull;

public class LoginAction  extends AnAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        LoginService instance = LoginService.getInstance();
        instance.startServer();

    }
}
