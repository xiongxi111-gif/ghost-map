package sample;

@interface Marker {
}

@Marker
class Sibling {
    private String name;

    String greet() {
        return name.strip();
    }
}
