# TODO List

Implement only the items that are not strikethrough:

1. ~~Make the openqasm view editable.~~
2. ~~Reflect on the canvas the changes in the openqasm editor~~
3. ~~Make the lines grey~~
4. ~~Replace the title OpenQASM 3 to Editor~~
5. ~~If in openqasm 3 is define a function and is used after that, on the canvas add a rectangle with the name of the function, instead of a gate.~~
6. ~~Add the option to import OpenQASM 3 code and draw the items on the canvas.~~
7. ~~Add the option to export the circuit as SVG.~~
8. ~~Split the implementation in multiple Clojure script files. There should be specific files that handles UI, that handle OpenQASM 3 parsing, Export, Save etc.~~
9. ~~Create unit tests with a target of over 50% code coverage. Try to reach 100 % code coverage if possible.~~
10. ~~Add handles to Edit menu. All submenus shall work in the Editor.~~
11. ~~Implement horizontal and vertical scroll in canvas.~~
12. ~~In the exported circuit (svg file), the vertical line of controlled not gate is extended both ways beyond the circuit wire.~~ 
13. ~~When using defgate in openqasm~~

```
gate cphase(θ) a, b
{
  U(0, 0, θ / 2) a;
  CX a, b;
  U(0, 0, -θ / 2) b;
  CX a, b;
  U(0, 0, θ / 2) b;
}
cphase(π / 2) q[0], q[1];
```
~~I'm expecting that if the gate is called, on the canvas to draw a rectangle on the used qubits with the name of the gate in the middle.~~

14. ~~Add symbol for measurement.~~
15. ~~Under the number of qubits, add a new input to select the number of classical bits.~~ 
16. ~~If the number of classical bits is greater than 0, update the canvas with a double line, and defined in QASM editor the classical bits.~~
17. ~~For the measurement operator, when added to the circuit, add vertical dotted line to the line of classical bits. If no classical bits are added, add them by default when the measurement operator is added to the circuit.~~
18. ~~Add the option to save the circuit by pressing CTRL-S or menu File->Save. If the circuit was not saved before by the user, the open a popup dialog to ask for the location of the save. Otherwise, overwrite the last save.~~
18. ~~If the user didn't save the project, create a snapshot every 1 minute, by saving a temp file in the folder where the last user save was done.~~
19. ~~If there is no user save, then do not create snapshots.~~
20. ~~File -> New shall create a new instance of the application.~~
21. ~~Rename Import Qasm to Open.~~
22. ~~Add Save As option~~
23. ~~Add in the toolbar the menu Preferences~~
24. ~~Under preferences add Settings~~
25. ~~On save, all temp saves shall be deleted automatically~~
26. ~~On measure, if there are more than 1 classical bits, don't add extra horizontal lines. make the change only in openqasm~~
27. ~~Remove the menu Export OpenQASM since is already done by save option~~
28. ~~Under settings add the option to change on dark mode.~~
29. ~~When the first measurement is added and there is a number of qubits n, do not automatically draw n lines for the classical bits. Draw only one line and in the editor add n classical bits.~~
30. ~~Remove the input for the number of classical bits~~
31. ~~In settings, add an input to configure the period for snapshot. Use that value instead of 1 minute~~
32. ~~On windows resize, the wires drawn on canvas do not automatically resize.~~
33. ~~Implement the Edit actions on the editor content.~~
34. ~~Add the barrier element~~
35. ~~OpenQASM's grammar is available on https://openqasm.com/grammar/index.html. Implement a module that will verify if the generated code in the editor respects the grammar.~~
36. ~~Show line numbers in editor~~