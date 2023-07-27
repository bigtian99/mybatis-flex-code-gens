package club.bigtian.mf.plugin.action.flex;

import club.bigtian.mf.plugin.core.Template;
import club.bigtian.mf.plugin.core.config.MybatisFlexConfig;
import club.bigtian.mf.plugin.windows.DatabaseInfoDialog;
import club.bigtian.mf.plugin.windows.MybatisFlexCodeGenerateDialog;
import cn.hutool.core.util.StrUtil;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;

/**
 * 表动作
 *
 * @author daijunxiong
 * @date 2023/06/22
 */
public class TableAction extends AnAction {
    //静态代码将在系统启动时执行

    @Override
    public void actionPerformed(AnActionEvent e) {
        MybatisFlexConfig config = Template.getMybatisFlexConfig();
        if (StrUtil.isEmpty(config.getUsername()) || StrUtil.isEmpty(config.getPassword()) || StrUtil.isEmpty(config.getUrl())) {
            DatabaseInfoDialog dialog = new DatabaseInfoDialog(e);
            dialog.setVisible(true);
        }
        config = Template.getMybatisFlexConfig();
        if (StrUtil.isEmpty(config.getUsername()) || StrUtil.isEmpty(config.getPassword()) || StrUtil.isEmpty(config.getUrl())) {
            return;
        }
        MybatisFlexCodeGenerateDialog generateWin = new MybatisFlexCodeGenerateDialog(e);
        generateWin.setVisible(true);
    }

//
//    /**
//     * 判断选中的是否是表，是表则显示，否则不显示
//     *
//     * @param e 事件
//     */
//    @Override
//    public void update(AnActionEvent e) {
//        Object selectedElement = e.getData(CommonDataKeys.PSI_ELEMENT);
//        boolean isSelectedTable = selectedElement instanceof DasTable;
//        e.getPresentation().setVisible(isSelectedTable);
//    }
}
