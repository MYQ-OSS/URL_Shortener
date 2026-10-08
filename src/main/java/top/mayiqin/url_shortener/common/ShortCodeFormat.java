package top.mayiqin.url_shortener.common;

/** 主版本只处理六位字母数字短码，不进行 ID 编解码或自定义加密。 */
public final class ShortCodeFormat {
    public static final int CODE_LENGTH = 6;
    private static final char[] ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    private ShortCodeFormat() { }

    public static char[] alphabet() { return ALPHABET.clone(); }

    public static boolean isWellFormed(String code) {
        if (code == null || code.length() != CODE_LENGTH) {
            return false;
        }
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z')) {
                return false;
            }
        }
        return true;
    }
}
