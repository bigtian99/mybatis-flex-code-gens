package club.bigtian.mf.plugin.action.flex;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;

public class PreviewSqlAction extends AnAction {

    @Override
    public void actionPerformed(AnActionEvent e) {
        // 获取IOC容器
        Editor editor = e.getData(CommonDataKeys.EDITOR);

        String selectedText = editor.getSelectionModel().getSelectedText();

    }


}
