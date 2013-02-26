    // Before:
    Module module = new AbstractModule() {
      @Override
      protected void configure() {
        bind(MyInterface.class).to(MyImplementation.class);
      }
    };

    // After:
    Module module = new ImmediateModule() {{
      bind(MyInterface.class).to(MyImplementation.class);
    }};
