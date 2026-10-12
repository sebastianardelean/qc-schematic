# TODO List

Implement only the items that are not strikethrough:

1. ~~Make the openqasm view editable.~~
2. ~~Reflect on the canvas the changes in the openqasm editor~~
3. Make the lines grey
4. Replace the title OpenQASM 3 to Editor
5. If in openqasm 3 is define a function and is used after that, on the canvas add a rectangle with the name of the function, instead of a gate. 
6. Add the option to import OpenQASM 3 code and draw the items on the canvas.
7. Add the option to export the circuit as SVG.
8. Split the implementation in multiple Clojure script files. There should be specific files that handles UI, that handle OpenQASM 3 parsing, Export, Save etc.