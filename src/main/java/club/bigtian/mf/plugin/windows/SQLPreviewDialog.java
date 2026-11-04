package club.bigtian.mf.plugin.windows;

import club.bigtian.mf.plugin.core.Template;
import club.bigtian.mf.plugin.core.config.MybatisFlexConfig;
import club.bigtian.mf.plugin.core.persistent.MybatisFlexPluginConfigData;
import club.bigtian.mf.plugin.core.util.*;
import cn.hutool.core.swing.clipboard.ClipboardUtil;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;

import javax.swing.*;
import java.awt.event.KeyEvent;

/**
 * sql预览窗口
 */
public class SQLPreviewDialog extends JDialog {
    private JPanel contentPane;
    private JButton buttonOK;
    private JButton buttonCancel;
    private JTextArea sqlPreview;
    private JButton exceuteButton;
    private JCheckBox isExecute;
    private String sql;
    private static final BasicFormatter FORMATTER = new BasicFormatter();
    AnActionEvent event;
    MybatisFlexConfig config;

    public SQLPreviewDialog(String sql, AnActionEvent event) {
        this(sql, event, null);
    }

    /**
     * @param meta 附加信息，会显示在标题上（例如 {@code fast 18ms} / {@code legacy}），方便直观看出走的是哪条链路
     */
    public SQLPreviewDialog(String sql, AnActionEvent event, String meta) {
        config = Template.getMybatisFlexConfig();
        isExecute.setSelected(config.isExecuteSql());
        setContentPane(contentPane);
        this.event = event;
        setModal(true);
        getRootPane().setDefaultButton(buttonOK);
        setTitle(meta == null || meta.isEmpty() ? "SQL Preview" : "SQL Preview · " + meta);
        setSize(500, 500);
        DialogUtil.centerShow(this);

        buttonOK.addActionListener(e -> onOK());

        buttonCancel.addActionListener(e -> onCancel());
        this.sql = FORMATTER.format(sql);
        sqlPreview.setText(this.sql);
        // call onCancel() when cross is clicked
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);

        // call onCancel() on ESCAPE
        contentPane.registerKeyboardAction(e -> onCancel(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        Project project = ProjectUtils.getCurrentProject();

        exceuteButton.addActionListener(e -> {
            onCancel();
            DbUtil.openDbConsole(sql,file -> {
                config.setExecuteSql(isExecute.isSelected());
                MybatisFlexPluginConfigData.setCurrentMybatisFlexConfig(config);
            });
        });
    }


    private void onOK() {
        ClipboardUtil.setStr(sql);
        NotificationUtils.notifySuccess("🎉SQL copied to clipboard.🎉");
        dispose();
    }

    private void onCancel() {
        // add your code here if necessary
        dispose();
    }
}
