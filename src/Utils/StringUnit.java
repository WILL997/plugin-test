package Utils;

import java.security.SecureRandom;

public class StringUnit {
    //用来产生随机字符串名字
    private static final String CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private static final SecureRandom RANDOM = new SecureRandom();



    // 将字符串转换为驼峰命名
    public static String toCamelCase(String className) {
        String[] parts = className.split("_");
        StringBuilder camelCaseString = new StringBuilder();
        for (String part : parts) {
            camelCaseString.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                camelCaseString.append(part.substring(1));
            }
        }
        return camelCaseString.toString();
    }


    //产生随机字符串名字
    public static String generateRandomString(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CHARACTERS.charAt(RANDOM.nextInt(CHARACTERS.length())));
        }
        return sb.toString();
    }
}
