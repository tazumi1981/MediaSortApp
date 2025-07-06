import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.mp4.Mp4Directory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * メディアファイル（JPEG/MP4）を撮影日時や作成日時で日付フォルダに振り分ける
 * Swing GUI アプリケーション。
 *
 * <p>機能：
 * <ul>
 * <li>コピー元、コピー先、エラー用コピー先の指定</li>
 * <li>サブフォルダを含めるかの指定（デフォルトON）</li>
 * <li>エラーファイルのコピー有無とエラー用コピー先の表示制御</li>
 * <li>コピー後の元ファイル削除オプション</li>
 * <li>コピー進捗表示、処理中止ボタン対応</li>
 * </ul>
 * </p>
 */
public class MediaSorterApp extends JFrame {
    private final JTextField sourceField = new JTextField(30);
    private final JTextField destField = new JTextField(30);
    private final JTextField errorField = new JTextField(30);
    private final JCheckBox deleteSourceCheck = new JCheckBox("コピー後に元ファイルを削除する");
    private final JCheckBox moveToErrorCheck = new JCheckBox("エラーファイルをコピーする");
    private final JCheckBox includeSubfoldersCheck = new JCheckBox("サブフォルダ内も含める");
    private final JTextArea logArea = new JTextArea(10, 60);
    private final JProgressBar progressBar = new JProgressBar();
    private final JLabel countLabel = new JLabel("コピー済み: 0 / 0  削除済み: 0");
    private volatile boolean cancelRequested = false;

    private JPanel errorFolderPanel;

    /**
     * コンストラクタ：GUIの初期化とイベント設定を行う
     */
    public MediaSorterApp() {
        super("メディア日付振り分けツール");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(800, 480);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        // コピー元フォルダ入力とサブフォルダ含めるチェックボックス
        panel.add(createInputPanel(createRequiredLabel("コピー元フォルダ:"), sourceField));
        includeSubfoldersCheck.setSelected(true);
        panel.add(includeSubfoldersCheck);

        // コピー先フォルダ入力
        panel.add(createInputPanel(createRequiredLabel("コピー先フォルダ:"), destField));

        // エラーファイルコピー用チェックボックスとその下にエラー用コピー先フォルダ入力欄
        panel.add(moveToErrorCheck);
        errorFolderPanel = createInputPanel(new JLabel("エラー用コピー先フォルダ:"), errorField);
        errorFolderPanel.setVisible(false);
        panel.add(errorFolderPanel);

        // コピー後の元ファイル削除チェックボックス
        panel.add(deleteSourceCheck);

        // 「エラーファイルをコピーする」チェックボックスの状態で
        // エラー用コピー先入力欄の表示/非表示切り替え
        moveToErrorCheck.addActionListener(e -> {
            errorFolderPanel.setVisible(moveToErrorCheck.isSelected());
            pack();
        });

        JButton executeButton = new JButton("実行");
        JButton cancelButton = new JButton("中止");

        executeButton.addActionListener(this::process);
        cancelButton.addActionListener(e -> cancelRequested = true);

        JPanel bottomPanel = new JPanel(new BorderLayout());
        JPanel buttonPanel = new JPanel();
        buttonPanel.add(executeButton);
        buttonPanel.add(cancelButton);

        bottomPanel.add(progressBar, BorderLayout.NORTH);
        bottomPanel.add(countLabel, BorderLayout.CENTER);
        bottomPanel.add(new JScrollPane(logArea), BorderLayout.SOUTH);

        add(panel, BorderLayout.NORTH);
        add(bottomPanel, BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.SOUTH);

        // 初期表示状態をセット（エラー用フォルダ入力欄は非表示）
        errorFolderPanel.setVisible(moveToErrorCheck.isSelected());
    }

    /**
     * 赤太字で必須項目を示すラベルを作成する
     *
     * @param labelText ラベルの文字列
     * @return 赤字太字のJLabel
     */
    private JLabel createRequiredLabel(String labelText) {
        return new JLabel("<html><span style='color:red; font-weight:bold;'>* " + labelText + "</span></html>");
    }

    /**
     * ラベルとテキストフィールド、参照ボタンをまとめた入力パネルを作成する
     *
     * @param label テキストラベル（JLabel）
     * @param field テキスト入力欄（JTextField）
     * @return 入力用JPanel
     */
    private JPanel createInputPanel(JLabel label, JTextField field) {
        JButton browse = new JButton("参照");
        browse.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                field.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT));
        p.add(label);
        p.add(field);
        p.add(browse);
        return p;
    }

    /**
     * 「実行」ボタン押下時の処理。
     * ファイル収集、撮影日時・作成日時による振り分け、コピー・削除処理を別スレッドで行う。
     * 進捗表示・ログ更新・エラー通知もここで管理。
     *
     * @param e アクションイベント
     */
    private void process(ActionEvent e) {
        cancelRequested = false;

        File sourceDir = new File(sourceField.getText());
        File destDir = new File(destField.getText());
        File errorDir = errorField.getText().isEmpty() ? null : new File(errorField.getText());

        // 入力値チェック
        if (!sourceDir.isDirectory()) {
            JOptionPane.showMessageDialog(this, "コピー元が正しくありません");
            return;
        }
        if (!destDir.isDirectory()) {
            JOptionPane.showMessageDialog(this, "コピー先が正しくありません");
            return;
        }
        if (moveToErrorCheck.isSelected() && (errorDir == null || !errorDir.isDirectory())) {
            JOptionPane.showMessageDialog(this, "エラー用コピー先フォルダが正しくありません");
            return;
        }

        new Thread(() -> {
            List<File> files = new ArrayList<>();
            collectFiles(sourceDir, files);

            List<File> errorFiles = new ArrayList<>();
            int total = files.size();
            int copied = 0;
            int deleted = 0;

            progressBar.setMinimum(0);
            progressBar.setMaximum(total);

            for (File file : files) {
                if (cancelRequested) break;

                try {
                    String dateStr = extractDate(file);
                    File targetFolder;

                    if (dateStr != null) {
                        targetFolder = new File(destDir, dateStr);
                    } else if (moveToErrorCheck.isSelected() && errorDir != null) {
                        String fallbackDate = formatDate(new Date(file.lastModified()));
                        targetFolder = new File(new File(errorDir, "error"), fallbackDate);
                    } else {
                        errorFiles.add(file);
                        continue;
                    }

                    targetFolder.mkdirs();

                    Files.copy(file.toPath(), new File(targetFolder, file.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
                    copied++;

                    if (deleteSourceCheck.isSelected()) {
                        file.delete();
                        deleted++;
                    }

                } catch (Exception ex) {
                    errorFiles.add(file);
                    ex.printStackTrace();
                }

                final int finalCopied = copied;
                final int finalDeleted = deleted;
                final int finalTotal = total;

                SwingUtilities.invokeLater(() -> {
                    progressBar.setValue(finalCopied);
                    countLabel.setText("コピー済み: " + finalCopied + " / " + finalTotal + "  削除済み: " + finalDeleted);
                });
            }

            final int finalCopied = copied;
            final int finalDeleted = deleted;
            final int finalTotal = total;

            SwingUtilities.invokeLater(() -> {
                logArea.append("\nコピー完了: " + finalCopied + " 件\n");
                logArea.append("削除完了: " + finalDeleted + " 件\n");
                if (!errorFiles.isEmpty()) {
                    logArea.append("エラー対象: " + errorFiles.size() + " 件\n");
                    for (File f : errorFiles) {
                        logArea.append(" - " + f.getAbsolutePath() + "\n");
                    }
                }
                if (cancelRequested) {
                    JOptionPane.showMessageDialog(this, "処理が中止されました。\n処理済み: " + finalCopied + " 件");
                } else {
                    JOptionPane.showMessageDialog(this, "仕分け完了しました。");
                }
            });
        }).start();
    }

    /**
     * 指定ディレクトリ配下の対象ファイル（.jpg/.mp4）を再帰的に収集する。
     * サブフォルダを含めるかはチェックボックスの状態で制御。
     *
     * @param dir 走査対象ディレクトリ
     * @param fileList ファイル収集リスト（呼び出し元で空リストを用意して渡す）
     */
    private void collectFiles(File dir, List<File> fileList) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                if (includeSubfoldersCheck.isSelected()) {
                    collectFiles(file, fileList);
                }
            } else {
                String name = file.getName().toLowerCase();
                if (name.endsWith(".jpg") || name.endsWith(".mp4")) {
                    fileList.add(file);
                }
            }
        }
    }

    /**
     * 画像・動画ファイルから撮影日時（JPEGの場合）またはメディア作成日時（MP4の場合）を取得する。
     * 撮影日時が取得できない場合は null を返す。
     *
     * @param file 対象ファイル
     * @return yyyy-MM-dd形式の日付文字列 または null
     * @throws Exception メタデータ取得エラーなど
     */
    private String extractDate(File file) throws Exception {
        Metadata metadata = ImageMetadataReader.readMetadata(file);

        if (file.getName().toLowerCase().endsWith(".jpg")) {
            ExifSubIFDDirectory dir = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
            Date date = dir != null ? dir.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL) : null;
            return (date != null) ? formatDate(date) : null;
        } else if (file.getName().toLowerCase().endsWith(".mp4")) {
            Mp4Directory dir = metadata.getFirstDirectoryOfType(Mp4Directory.class);
            Date date = dir != null ? dir.getDate(Mp4Directory.TAG_CREATION_TIME) : null;
            return (date != null) ? formatDate(date) : null;
        }
        return null;
    }

    /**
     * Date型を yyyy-MM-dd の文字列にフォーマットする。
     *
     * @param date 対象日時
     * @return フォーマット済み文字列
     */
    private String formatDate(Date date) {
        return new SimpleDateFormat("yyyy-MM-dd").format(date);
    }

    /**
     * メインメソッド。Swing のイベントディスパッチスレッドでGUIを起動する。
     *
     * @param args コマンドライン引数（未使用）
     */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new MediaSorterApp().setVisible(true));
    }
}
